package cuhk.asgn;

import cuhk.asgn.runnable.*;
import raft.Raft;

import java.sql.Time;
import java.util.*;
import java.util.concurrent.*;

/**
 * @program: asgn
 * @description:
 * @author: Mr.Wang
 * @create: 2022-04-07 18:48
 **/
public class TaskHolder {
    public ScheduledThreadPoolExecutor threadPoolExecutor = new ScheduledThreadPoolExecutor(5);
    public ThreadPoolExecutor executor = new ThreadPoolExecutor(16, 30, 1, TimeUnit.MINUTES,
            new ArrayBlockingQueue(30), new ThreadPoolExecutor.AbortPolicy());
    public ElectionTask electionTask;
    public HeartBeatTask heartBeatTask;
    HashMap<Integer, ScheduledFuture> heartBeatMap;
    ScheduledFuture<?> electionFuture;
    ScheduledFuture<?> heartFuture;
    HashMap<Integer, HeartBeatTask> remainTask;
    TimerManager timerManager;
    List<Waiter> waiterList = new ArrayList<>();
    State state;
    public RaftRunner.RaftNode raftNode;

    TaskHolder(State state, RaftRunner.RaftNode raftNode) {
        this.heartBeatMap = new HashMap<>();
        remainTask = new HashMap<>();
        this.raftNode = raftNode;
        timerManager = new TimerManager(state);
        this.state = state;
    }

    public void addElection() {
        System.out.println("node" + state.getNodeId() + "reset election");
        if (electionFuture != null) {
            electionFuture.cancel(true);
        }
        this.electionTask = new ElectionTask(this.state, executor, raftNode);
        electionFuture = threadPoolExecutor.scheduleAtFixedRate(electionTask, Variables.electionTimeout, Variables.electionTimeout, TimeUnit.MILLISECONDS);
    }

    public Runnable leaderTask() {
        final TaskHolder taskHolder =this;
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Runnable runnable = new LeaderTask(state, executor, taskHolder);
                executor.submit(runnable);
                System.out.println("submit leader work");
                try {
//            set = future.get(150,TimeUnit.MILLISECONDS);
//            success = state.getHostConnectionMap().size() - remainTask.size()-1;
//            this.addHeartBeatWithDelay();
                } catch (Exception e) {
                    System.out.println("there is an append entry fail");
                    e.printStackTrace();
                }
                returnCheck();
            }
        };
        return runnable;
    }

    public void addHeartBeat() {
        startHeartBeat();
//        if(heartFuture!=null){
//            heartFuture.cancel(true);
//            heartFuture = null;
//        }
//        if (!this.state.getRole().equals(Raft.Role.Leader)) {
//            return;
//        }
//        this.heartBeatTask= new HeartBeatTask(this.state,executor,this.remainTask,this);
//        heartFuture = threadPoolExecutor.scheduleAtFixedRate(heartBeatTask,0,Variables.heartBeatInterval, TimeUnit.MILLISECONDS);
    }

    public void startHeartBeat() {
        for (int i = 0; i < state.getHostConnectionMap().size(); i++) {
            if (state.getNodeId() == i) {
                continue;
            }
            HeartTask heartTask = new HeartTask(state, i, this);
            System.out.println("add heartbeat:"+i);
            threadPoolExecutor.scheduleAtFixedRate(
                    heartTask, 0, Variables.heartBeatInterval, TimeUnit.MILLISECONDS);
        }
    }

    public void stopBeat() {
        for (Map.Entry<Integer, ScheduledFuture> entry : heartBeatMap.entrySet()) {
            entry.getValue().cancel(true);
        }
        heartBeatMap = new HashMap<>();
    }

    public void resetBeat(final int nodeId) {
        if (!this.state.getRole().equals(Raft.Role.Leader)) {
            return;
        }
        ScheduledFuture scheduledFuture = heartBeatMap.get(nodeId);
        if (scheduledFuture != null) scheduledFuture.cancel(true);
        System.out.println("node" + nodeId + "leader reset heartBeat");
        heartBeatMap.put(nodeId, threadPoolExecutor.scheduleWithFixedDelay(
                new HeartTask(state, nodeId, this), Variables.heartBeatInterval, Variables.heartBeatInterval, TimeUnit.MILLISECONDS));
    }

    public void addHeartBeatWithDelay() {
//        if (!this.state.getRole().equals(Raft.Role.Leader)) {
//            return;
//        }
        for (int i = 0; i < state.getHostConnectionMap().size(); i++) {
            if (state.getNodeId() == i) {
                continue;
            }
            this.resetBeat(i);
        }
//        if(heartFuture!=null){
//            heartFuture.cancel(true);
//            heartFuture = null;
//        }
//        if (!this.state.getRole().equals(Raft.Role.Leader)) {
//            return;
//        }
//        heartFuture = threadPoolExecutor.scheduleAtFixedRate(heartBeatTask,Variables.heartBeatInterval,Variables.heartBeatInterval, TimeUnit.MILLISECONDS);
    }

    public void stopHeartBeat() {
        stopBeat();
//        if(heartFuture!=null){
//            heartFuture.cancel(true);
//        }
//        heartFuture = null;
    }

    public void stopElection() {
        System.out.println("node" + state.getNodeId() + "stop election");
        if (electionFuture != null) electionFuture.cancel(true);
        electionFuture = null;
    }

    public boolean waitUntilMajority(int cur) {
        while(true){
            if(state.getCommitIndex().get()>cur){
                break;
            }
        }
        returnCheck();
        return true;
    }

    public boolean waiterCheck(Waiter waiter) {
        if (waiter.couldReturn()) {
            if (state.log.size() == 0) {

            } else {
                if (state.getRole().equals(Raft.Role.Leader)) {
                    raftNode.leaderRefresh();
                } else {
                    raftNode.refresh(state.commitIndex.get());
//                    state.commitIndex.getAndIncrement();
                }
            }
            return true;
        }
        return false;
    }

    public synchronized void returnCheck() {
        int cur = state.getCommitIndex().get();
        int size = state.getHostConnectionMap().size();
        int counter = 0;
        for (int i = 0; i < size; i++) {
            if (i == state.getNodeId()) {
                continue;
            }
            int match = state.getMatchIndex()[i];
            if (match > cur) {
                counter++;
            }
        }
        if (cur < state.getLog().size() && (counter + 1 > size / 2)) {
            System.out.println("leader commit log" + cur);
            state.getCommitIndex().getAndIncrement();
            raftNode.leaderRefresh();
        }
    }

}
