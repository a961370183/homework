package cuhk.asgn.runnable;

import cuhk.asgn.RaftRunner;
import cuhk.asgn.State;
import cuhk.asgn.Variables;
import raft.Raft;
import raft.RaftNodeGrpc;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @program: asgn
 * @description:
 * @author: Mr.Wang
 * @create: 2022-04-07 18:02
 **/
public class ElectionTask implements Runnable{
    ThreadPoolExecutor threadPoolExecutor;
    State state;
    RaftRunner.RaftNode raftNode;
    public long pre = 0;
    public ElectionTask(State state, ThreadPoolExecutor threadPoolExecutor, RaftRunner.RaftNode raftNode){
        this.threadPoolExecutor = threadPoolExecutor;
        this.state = state;
        this.raftNode = raftNode;
        pre = System.currentTimeMillis();
    }
    @Override
    public void run() {
        System.out.println("node"+state.getNodeId()+"start election");
        if (state.getRole() == Raft.Role.Leader) {
            return;
        }
        state.setRole(Raft.Role.Candidate);
        state.getCurrentTerm().getAndIncrement();
        long current = System.currentTimeMillis();
        pre = current;
        //start election
        final ArrayList<Future<Raft.RequestVoteReply>> list = new ArrayList<>();
        for (Map.Entry<Integer, RaftNodeGrpc.RaftNodeBlockingStub> entry : state.getHostConnectionMap().entrySet()) {
            final int nodeId = entry.getKey();
            final RaftNodeGrpc.RaftNodeBlockingStub stub = entry.getValue();
            if (nodeId == state.getNodeId()) {
                //not send to myself;
                continue;
            }
            list.add(threadPoolExecutor.submit(new Callable() {
                @Override
                public Raft.RequestVoteReply call() {
                    Raft.RequestVoteArgs requestVoteArgs = Raft.RequestVoteArgs.newBuilder()
                            .setTerm(state.getCurrentTerm().get())
                            .setCandidateId(state.getNodeId())
                            .setFrom(state.getNodeId())
                            .setTo(nodeId)
                            .setLastLogTerm(state.getLastLogTerm())
                            .setLastLogIndex(state.getCommitIndex().get())
                            .build();
                    try {
                        Raft.RequestVoteReply r = stub.requestVote(requestVoteArgs);
                        return r;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                }
            }));
        }
        final boolean[] termOutDate = new boolean[1];
        int total = state.hostConnectionMap.size();
        //init to 1, vote for myself
        final AtomicInteger success = new AtomicInteger(1);
        final AtomicInteger counter = new AtomicInteger(1);
        state.setVotedFor(state.getNodeId());
        for (final Future<Raft.RequestVoteReply> future : list) {
            threadPoolExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Raft.RequestVoteReply r = future.get(200, TimeUnit.MILLISECONDS);
                        if (r == null) {
                            //fail to get Reply
                        }
                        if(r.getTerm()>state.getCurrentTerm().get()){
                            //return
                            termOutDate[0] = true;
                            state.getCurrentTerm().set(r.getTerm());
                            state.setRole(Raft.Role.Follower);
                        }
                        if (r.getVoteGranted()) {
                            success.getAndIncrement();
                        }

                    } catch (Exception e) {
                        System.out.println("node:"+state.getNodeId()+"can't connect to a node");
                        e.printStackTrace();
                    }finally {
                        counter.getAndIncrement();
                    }
                }
            });
        }
        while(counter.get()<total){

        }
        if(termOutDate[0]){
            return;
        }
        if (success.get() > total / 2) {
            //win the election
            System.out.println(state.getNodeId()+"become leader");
            state.setRole(Raft.Role.Leader);
            state.setLeaderId(state.getNodeId());
            raftNode.leaderInit();
        }
    }
}
