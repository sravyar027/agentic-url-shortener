package demo.urlshortener.orchestration;
import java.util.*;
/** Computes the minimum affected subgraph when an approved upstream decision changes. */
public final class WorkflowPlanner {
  private WorkflowPlanner() { }
  public static Set<String> transitiveDependents(Collection<WorkflowEngine.Node> nodes,String changed){
    Map<String,Set<String>> children=new HashMap<>();for(var n:nodes)for(String dependency:n.dependsOn())children.computeIfAbsent(dependency,k->new LinkedHashSet<>()).add(n.id());
    Set<String> affected=new LinkedHashSet<>();Deque<String> todo=new ArrayDeque<>();todo.add(changed);while(!todo.isEmpty()){String current=todo.remove();if(affected.add(current))todo.addAll(children.getOrDefault(current,Set.of()));}return affected;
  }
}
