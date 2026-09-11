package demo.urlshortener.orchestration;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.nio.file.*;
import java.io.IOException;

/** Explicit DAG executor: policy checked, audit logged, approval gated, bounded retry and safe-stop aware. */
public final class WorkflowEngine {
  public enum Status { PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, ROLLED_BACK, SKIPPED }
  public record Node(String id, Set<String> dependsOn, boolean approvalRequired, int maxAttempts, Supplier<String> action, Runnable rollback) { }
  public record AuditEvent(Instant at,String node,Status status,String detail) { }
  public record Run(Map<String,Status> states,List<AuditEvent> audit,Map<String,String> outputs,Duration latency,int retries,int rollbacks) { }
  public interface Policy { Optional<String> deny(Node node); }
  /** Append-only local audit adapter. A real deployment sends the same events to an immutable remote store. */
  public static final class AuditJournal { private final Path file; public AuditJournal(Path file){this.file=file;} void append(AuditEvent e){try{Files.createDirectories(file.getParent());Files.writeString(file,e.at()+"|"+e.node()+"|"+e.status()+"|"+e.detail()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException x){throw new IllegalStateException("audit persistence failed",x);}} }
  private final Map<String,Node> graph; private final Set<String> approvals; private final List<AuditEvent> audit=new CopyOnWriteArrayList<>(); private final Map<String,String> outputs=new ConcurrentHashMap<>(); private final Policy policy; private final AuditJournal journal;
  public WorkflowEngine(Collection<Node> nodes, Set<String> approvals){this(nodes,approvals,n->Optional.empty(),null);}
  public WorkflowEngine(Collection<Node> nodes, Set<String> approvals, Policy policy, AuditJournal journal){graph=new LinkedHashMap<>();nodes.forEach(n->{if(graph.put(n.id(),n)!=null)throw new IllegalArgumentException("duplicate node");});this.approvals=Set.copyOf(approvals);this.policy=policy;this.journal=journal; validate();}
  public Run execute() {
    Instant start=Instant.now(); Map<String,Status> state=new ConcurrentHashMap<>();graph.keySet().forEach(k->state.put(k,Status.PENDING)); int[] counters=new int[2];
    ExecutorService pool=Executors.newFixedThreadPool(Math.min(4,graph.size())); try { boolean progress=true; while(progress){progress=false; List<Future<?>> futures=new ArrayList<>(); for(Node n:graph.values()) if(state.get(n.id())==Status.PENDING&&n.dependsOn().stream().allMatch(d->state.get(d)==Status.SUCCEEDED)){ progress=true; if(n.approvalRequired()&&!approvals.contains(n.id())){transition(state,n.id(),Status.WAITING_APPROVAL,"human approval required");continue;} futures.add(pool.submit(()->runNode(state,n,counters))); } for(Future<?> f:futures)f.get(); } } catch(Exception e){throw new IllegalStateException("workflow execution interrupted",e);} finally {pool.shutdown();}
    for(Node n:graph.values()) if(state.get(n.id())==Status.PENDING) transition(state,n.id(),Status.SKIPPED,"upstream gate did not pass");
    return new Run(Map.copyOf(state),List.copyOf(audit),Map.copyOf(outputs),Duration.between(start,Instant.now()),counters[0],counters[1]);
  }
  private void runNode(Map<String,Status>s,Node n,int[] c){Optional<String> denial=policy.deny(n);if(denial.isPresent()){transition(s,n.id(),Status.FAILED,"policy-denied: "+denial.get());return;}transition(s,n.id(),Status.RUNNING,"policy=passed");for(int attempt=1;attempt<=n.maxAttempts();attempt++)try{String output=n.action().get();outputs.put(n.id(),output);transition(s,n.id(),Status.SUCCEEDED,"attempt="+attempt);return;}catch(RuntimeException ex){if(attempt<n.maxAttempts()){synchronized(c){c[0]++;}event(n.id(),Status.RUNNING,"retry="+attempt+": "+ex.getMessage());}else{transition(s,n.id(),Status.FAILED,"safe-stop: "+ex.getMessage());if(n.rollback()!=null){n.rollback().run();synchronized(c){c[1]++;}transition(s,n.id(),Status.ROLLED_BACK,"rollback complete");}}}}
  private void validate(){for(Node n:graph.values())for(String d:n.dependsOn())if(!graph.containsKey(d))throw new IllegalArgumentException("unknown dependency "+d);}
  private void transition(Map<String,Status>s,String node,Status status,String detail){s.put(node,status);event(node,status,detail);} private void event(String node,Status s,String d){AuditEvent e=new AuditEvent(Instant.now(),node,s,d);audit.add(e);if(journal!=null)journal.append(e);}
}
