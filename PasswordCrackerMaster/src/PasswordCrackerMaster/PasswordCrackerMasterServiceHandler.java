package PasswordCrackerMaster;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TSocket;
import thrift.gen.PasswordCrackerMasterService.PasswordCrackerMasterService;
import thrift.gen.PasswordCrackerWorkerService.PasswordCrackerWorkerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.*;

import static PasswordCrackerMaster.PasswordCrackerConts.SUB_RANGE_SIZE;
import static PasswordCrackerMaster.PasswordCrackerConts.WORKER_PORT;
import static PasswordCrackerMaster.PasswordCrackerConts.NUMBER_OF_WORKER;
import static PasswordCrackerMaster.PasswordCrackerMasterServiceHandler.jobInfoMap;
import static PasswordCrackerMaster.PasswordCrackerMasterServiceHandler.workersAddressList;
import static PasswordCrackerMaster.PasswordCrackerMasterServiceHandler.taskMap;

public class PasswordCrackerMasterServiceHandler implements PasswordCrackerMasterService.Iface {
    public static List<TSocket> workersSocketList = new LinkedList<>();  //Connected Socket
    public static List<String> workersAddressList = new LinkedList<>(); // Connected WorkerAddress
    public static ConcurrentHashMap<String, PasswordDecrypterJob> jobInfoMap = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Long> latestHeartbeatInMillis = new ConcurrentHashMap<>(); // <workerAddress, time>
    public static ExecutorService workerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    public static ScheduledExecutorService heartBeatCheckPool = Executors.newScheduledThreadPool(1);
    public static ConcurrentHashMap<String, List<PasswordTask>> taskMap = new ConcurrentHashMap<>(); // <IP addr, list_tasks>
    public static Lock lock = new ReentrantLock(); //We need to synchronize those static member variables

    /*
     * The decrypt method create the job and put the job with jobId (encrypted Password) in map.
     * And call the requestFindPassword and if it finds the password, it return the password to the client.
     */
    @Override
    public String decrypt(String encryptedPassword) throws TException {
        PasswordDecrypterJob decryptJob = new PasswordDecrypterJob();
        jobInfoMap.put(encryptedPassword, decryptJob);
        requestFindPassword(encryptedPassword, 0l, SUB_RANGE_SIZE);

        String password = decryptJob.getPassword();
        jobInfoMap.remove(encryptedPassword);
        return password; 
    }

    /*
     * The reportHeartBeat receives the Heartbeat from workers.
     * Consider the checkHeartBeat method and use latestHeartbeatInMillis map.
    */
    @Override
    public void reportHeartBeat(String workerAddress)
            throws TException {
            latestHeartbeatInMillis.put(workerAddress, System.currentTimeMillis());
    }

    /*
     * The requestFindPassword requests workers to find password using RPC in asynchronous way.
    */
    public static void requestFindPassword(String encryptedPassword, long rangeBegin, long subRangeSize) {
        PasswordCrackerWorkerService.AsyncClient worker = null;
        FindPasswordMethodCallback findPasswordCallBack = new FindPasswordMethodCallback(encryptedPassword);

        lock.lock(); // Mutual exclusion is needed here
        try {
            for (int taskId = 0; taskId < NUMBER_OF_WORKER; taskId++) {
                int workerId = taskId % workersAddressList.size();
                String workerAddress = workersAddressList.get(workerId);

                long subRangeBegin = rangeBegin + (taskId * subRangeSize);
                long subRangeEnd = subRangeBegin + subRangeSize;

                worker = new PasswordCrackerWorkerService.AsyncClient(
                        new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(workerAddress, WORKER_PORT));
                worker.startFindPasswordInRange(subRangeBegin, subRangeEnd, encryptedPassword, findPasswordCallBack);

                taskMap.putIfAbsent(workerAddress, new LinkedList<>());
                taskMap.get(workerAddress).add(new PasswordTask(subRangeBegin, subRangeEnd, workerId, encryptedPassword));
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        catch (TException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    /*
     * The redistributeFailedTask distributes the dead workers's job (or a set of possible password) to active workers.
     *
     * Check the checkHeartBeat method
     */
    public static void redistributeFailedTask(ArrayList<Integer> failedWorkerIdList) {

        // Indices of the array must be removed reverserly
        // Fill up badAddresses with those very bad addresses
        Collections.reverse(failedWorkerIdList);
        ArrayList<String> badAddresses = new ArrayList<>();
        for (Integer workerIdInteger : failedWorkerIdList) {
            badAddresses.add(workersAddressList.get(workerIdInteger.intValue()));
            workersAddressList.remove(workerIdInteger.intValue());
        }

        // For each of the jobs 
        PasswordCrackerWorkerService.AsyncClient worker = null;
        for (String faultyWorkerAddress : badAddresses) {
            List<PasswordTask> listTask = taskMap.get(faultyWorkerAddress);
            taskMap.remove(faultyWorkerAddress);
            for (PasswordTask task : listTask) {
                    // Compute new workerId
                    int workerId = task.workerId % workersAddressList.size();
                    String newWorkerAddress = workersAddressList.get(workerId);

                    System.out.println("Reassigning task: " + task.workerId + " from job: " + task.encryptedPassword + 
                            " to workerId:" + workerId + "IP: " + newWorkerAddress);

                    task.workerId = workerId;

                    // Move the task to a new worker
                    taskMap.putIfAbsent(newWorkerAddress, new LinkedList<>());
                    taskMap.get(newWorkerAddress).add(task);

                    FindPasswordMethodCallback findPasswordCallBack = new FindPasswordMethodCallback(task.encryptedPassword);

                try {
                    worker = new PasswordCrackerWorkerService.AsyncClient(
                            new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(newWorkerAddress, WORKER_PORT));
                    worker.startFindPasswordInRange(task.lowerBoundary, task.upperBoundary, task.encryptedPassword, findPasswordCallBack);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                catch (TException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /*
     *  If the master didn't receive the "HeartBeat" in 5 seconds from any workers,
     *  it considers the workers that didn't send the "HeartBeat" as dead.
     *  And then, it redistributes the dead worker's job in other alive workers
     *
     *  hint : use latestHeartbeatinMillis, workersAddressList
     *
     *  you must think about when several workers is dead.
     *
     *  and use the workerPool
     */
    public static void checkHeartBeat() {
        int workerId = 0;
        final long thresholdAge = 5_000;
        long currentTime = System.currentTimeMillis();

        ArrayList<Integer> failedWorkerIdList = new ArrayList<>();

        // Fill up failedWorkerIdList
        for (String addr : workersAddressList) {
          long originTime = latestHeartbeatInMillis.get(addr);
          long timeElapsed = currentTime - originTime;
          if (timeElapsed > thresholdAge) {
            failedWorkerIdList.add(workerId);
            System.out.println("Lost Worker [Addr " + addr + " workerid: " + workerId + "time " + timeElapsed + "]");
          }

          workerId++;
        }
        lock.lock();   // Mutual exclusion is needed here
        redistributeFailedTask(failedWorkerIdList);
        lock.unlock(); 
    }
}

//CallBack
class FindPasswordMethodCallback implements AsyncMethodCallback<PasswordCrackerWorkerService.AsyncClient.startFindPasswordInRange_call> {
    private String jobId;

    FindPasswordMethodCallback(String jobId) {
        this.jobId = jobId;
    }

    /*
     *  if the returned result from worker is not null, it completes the job.
     *  and call the jobTermination method
     */
    @Override
    public void onComplete(PasswordCrackerWorkerService.AsyncClient.startFindPasswordInRange_call startFindPasswordInRange_call) {
        try {
            String findPasswordResult = startFindPasswordInRange_call.getResult();

            if (findPasswordResult != null) {
                jobTermination(jobId);
                PasswordDecrypterJob futureJob = jobInfoMap.get(jobId);
                futureJob.setPassword(findPasswordResult);

                // I know, a nested loop is never a good thing.
                // But look, there are at most 8 workes and <40 tasks

                // Remove tasks related to this job
                taskMap.forEach((workerAddress, listTasks) ->  {
                    Iterator<PasswordTask> it = listTasks.iterator();
                    while (it.hasNext()) {
                        PasswordTask task = it.next();
                        if (task.encryptedPassword.equals(jobId)){
                            it.remove();
                        }
                    }
                });
            }

        }
        catch (TException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(Exception e) {
        System.out.println("Error : startFindPasswordInRange of FindPasswordMethodCallback");
    }

    /*
     *  The jobTermination transfer the termination signal to workers in asynchronous way
     */
    private void jobTermination(String jobId) {
        try {
            PasswordCrackerWorkerService.AsyncClient worker = null;
            for (String workerAddress : workersAddressList) {
                worker = new PasswordCrackerWorkerService.
                        AsyncClient(new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(workerAddress, WORKER_PORT));

                worker.reportTermination(jobId, new AsyncMethodCallback<PasswordCrackerWorkerService.AsyncClient.reportTermination_call>() {
                  @Override
                  public void onComplete(PasswordCrackerWorkerService.AsyncClient.reportTermination_call termination) {
                  }

                  @Override
                  public void onError(Exception e) {
                    System.out.println("Error : reportTermination " + e.getMessage());
                  }
                
                });
            }
        }
        catch (TException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}


class PasswordTask {
        public final long lowerBoundary;
        public final long upperBoundary;
        public final String encryptedPassword;

        public int workerId;
        PasswordTask(long lowerBoundary, long upperBoundary, int workerId, String encryptedPassword) {
            this.lowerBoundary = lowerBoundary;
            this.upperBoundary = upperBoundary;
            this.workerId = workerId;
            this.encryptedPassword = encryptedPassword;
        }
    }
