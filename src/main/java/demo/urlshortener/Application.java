package demo.urlshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import demo.urlshortener.orchestration.DemoWorkflow;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Small dependency-free HTTP service; replace InMemoryUrlRepository with a durable adapter in production. */
public final class Application {
  private final LocalPlatform.Metrics metrics = new LocalPlatform.Metrics();
  private final Path dataDir = Path.of(System.getProperty("data.dir", "data"));
  private final LocalPlatform.UrlPolicy policy = new LocalPlatform.UrlPolicy();
  private final LocalPlatform.FixedWindowRateLimiter creationLimit = new LocalPlatform.FixedWindowRateLimiter(30, java.time.Duration.ofMinutes(1));
  private final LocalPlatform.IdempotencyStore idempotency = new LocalPlatform.IdempotencyStore(dataDir.resolve("idempotency.bin"));
  private final LocalPlatform.LocalApproval approvals = new LocalPlatform.LocalApproval();
  private final LocalPlatform.Auth auth = new LocalPlatform.Auth();
  private final UrlService urls = new UrlService(new LocalPlatform.DurableUrlRepository(dataDir.resolve("links.bin")), new ShortCodeGenerator(),new LocalPlatform.LinkCache(10_000,java.time.Duration.ofMinutes(5)));
  public static void main(String[] args) throws IOException { new Application().start(Integer.parseInt(System.getProperty("port", "8080"))); }
  void start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", e -> reply(e, 200, "{\"status\":\"UP\"}"));
    server.createContext("/metrics", e -> metrics(e));
    server.createContext("/api/urls", this::api);
    server.createContext("/workflow/demo", e -> workflowRead(e,false));
    server.createContext("/workflow/release", e -> workflowRelease(e));
    server.createContext("/workflow/replan", e -> workflowRead(e,true));
    server.createContext("/", this::redirect);
    server.setExecutor(Executors.newCachedThreadPool()); server.start();
    System.out.println("URL shortener listening on http://localhost:" + port);
  }
  private void api(HttpExchange e) throws IOException {
    e.getResponseHeaders().set("X-Request-Id", Optional.ofNullable(e.getRequestHeaders().getFirst("X-Request-Id")).orElse(UUID.randomUUID().toString()));
    String path = e.getRequestURI().getPath();
    try {
      if ("POST".equals(e.getRequestMethod()) && "/api/urls".equals(path)) {
        LocalPlatform.Principal principal=require(e,LocalPlatform.Role.USER);
        String client = Optional.ofNullable(e.getRequestHeaders().getFirst("X-Forwarded-For")).orElse(e.getRemoteAddress().getAddress().getHostAddress());
        if (!creationLimit.allow(client)) { metrics.count("rate_limited_total"); reply(e,429,"{\"error\":\"rate_limited\"}");return; }
        String longUrl = Json.field(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), "url");
        policy.validateCreate(longUrl);
        String idempotencyKey=e.getRequestHeaders().getFirst("Idempotency-Key");
        UrlLink link = idempotency.create(idempotencyKey,longUrl,()->urls.shorten(longUrl,principal.subject()));
        String host = e.getRequestHeaders().getFirst("Host"); if (host == null) host = "localhost:8080";
        metrics.count("links_created_total"); reply(e, 201, "{\"code\":\""+link.code()+"\",\"shortUrl\":\"http://"+host+"/"+link.code()+"\",\"createdAt\":\""+link.createdAt()+"\"}"); return;
      }
      String[] parts = path.split("/");
      if ("GET".equals(e.getRequestMethod()) && parts.length == 5 && "analytics".equals(parts[4])) {
        LocalPlatform.Principal principal=authenticate(e); UrlLink link = urls.analytics(parts[3]); auth.requireOwnerOrReview(principal,link); metrics.count("analytics_reads_total"); reply(e, 200, "{\"code\":\""+link.code()+"\",\"url\":\""+Json.escape(link.longUrl())+"\",\"visits\":"+link.visits()+"}"); return;
      }
      reply(e, 404, "{\"error\":\"not_found\"}");
    } catch (LocalPlatform.AccessDenied ex) { metrics.count(ex.status==401?"authentication_failures_total":"authorization_denied_total");reply(e,ex.status,"{\"error\":\""+ex.getMessage()+"\"}"); }
    catch (IllegalArgumentException ex) { metrics.count("validation_failures_total"); reply(e, 400, "{\"error\":\""+Json.escape(ex.getMessage())+"\"}"); }
  }
  private void redirect(HttpExchange e) throws IOException {
    if (!"GET".equals(e.getRequestMethod()) || e.getRequestURI().getPath().length() < 2) { reply(e, 404, "{\"error\":\"not_found\"}"); return; }
    try { UrlLink link = urls.resolve(e.getRequestURI().getPath().substring(1)); metrics.count("redirects_total"); e.getResponseHeaders().add("Location", link.longUrl()); e.sendResponseHeaders(302, -1); }
    catch (IllegalArgumentException ex) { metrics.count("not_found_total"); reply(e, 404, "{\"error\":\"not_found\"}"); }
  }
  private void metrics(HttpExchange e) throws IOException { byte[] body=metrics.prometheus().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","text/plain; version=0.0.4");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close(); }
  private void workflowRead(HttpExchange e,boolean replan) throws IOException {try{require(e,LocalPlatform.Role.REVIEWER);reply(e,200,replan?DemoWorkflow.replanPreview():DemoWorkflow.run(false));}catch(LocalPlatform.AccessDenied ex){reply(e,ex.status,"{\"error\":\""+ex.getMessage()+"\"}");}}
  private void workflowRelease(HttpExchange e) throws IOException { try {if(!"POST".equals(e.getRequestMethod())){reply(e,405,"{\"error\":\"method_not_allowed\"}");return;} LocalPlatform.Principal p=require(e,LocalPlatform.Role.RELEASE_MANAGER); boolean approved="APPROVED".equals(e.getRequestHeaders().getFirst("X-Change-Approval"))&&approvals.permitted(p.subject(),e.getRequestHeaders().getFirst("X-Approval-Token")); if(!approved){metrics.count("approval_denied_total");reply(e,403,"{\"error\":\"approval_not_authorized\"}");return;} metrics.count("approval_granted_total"); reply(e,200,DemoWorkflow.run(true));}catch(LocalPlatform.AccessDenied ex){metrics.count(ex.status==401?"authentication_failures_total":"authorization_denied_total");reply(e,ex.status,"{\"error\":\""+ex.getMessage()+"\"}");} }
  private LocalPlatform.Principal authenticate(HttpExchange e){return auth.authenticate(e.getRequestHeaders().getFirst("X-Api-Key"));}
  private LocalPlatform.Principal require(HttpExchange e,LocalPlatform.Role role){LocalPlatform.Principal p=authenticate(e);auth.require(p,role);return p;}
  static void reply(HttpExchange e, int status, String body) throws IOException { byte[] bytes=body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().add("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
  static final class Json { static String field(String body,String name) { String marker="\""+name+"\""; int a=body.indexOf(marker); if(a<0) throw new IllegalArgumentException("missing "+name); int q=body.indexOf('"',body.indexOf(':',a)+1), z=body.indexOf('"',q+1); if(q<0||z<0)throw new IllegalArgumentException("invalid JSON"); return body.substring(q+1,z); } static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"");} }
}
