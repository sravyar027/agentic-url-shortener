package demo.urlshortener.orchestration;
import java.util.*;import java.nio.file.Path;
/** Demonstrates parallel design work synchronising at the implementation gate, then human-controlled release. */
public final class DemoWorkflow {
  private static List<WorkflowEngine.Node> nodes(){return List.of(
      new WorkflowEngine.Node("understand",Set.of(),false,1,()->"Normalized requirement v1; custom aliases deferred",null),
      new WorkflowEngine.Node("architecture",Set.of("understand"),false,1,()->"API + repository boundary selected",null),
      new WorkflowEngine.Node("threat-model",Set.of("understand"),false,1,()->"SSRF and open redirect controls recorded",null),
      new WorkflowEngine.Node("implement",Set.of("architecture","threat-model"),false,2,()->"service implementation generated",()->{}),
      new WorkflowEngine.Node("validate",Set.of("implement"),false,1,()->"unit and endpoint tests passed",null),
      new WorkflowEngine.Node("release",Set.of("validate"),true,1,()->"release candidate approved",null));}
  public static String run(boolean approved){
    var policy=(WorkflowEngine.Policy)n->n.id().equals("release")&&!n.approvalRequired()?Optional.of("release must be approval-gated"):Optional.empty();
    var r=new WorkflowEngine(nodes(),approved?Set.of("release"):Set.of(),policy,new WorkflowEngine.AuditJournal(Path.of(System.getProperty("data.dir","data"),"workflow-audit.log"))).execute(); return "{\"states\":\""+r.states()+"\",\"retries\":"+r.retries()+",\"rollbacks\":"+r.rollbacks()+",\"latencyMs\":"+r.latency().toMillis()+"}";
  }
  public static String replanPreview(){return "{\"changedNode\":\"threat-model\",\"invalidated\":\""+WorkflowPlanner.transitiveDependents(nodes(),"threat-model")+"\",\"governance\":\"re-run validation and release approval\"}";}
}
