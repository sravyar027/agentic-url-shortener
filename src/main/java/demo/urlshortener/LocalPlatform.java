package demo.urlshortener;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Local production-shaped adapters: durable snapshots, policy boundary, rate limiting and metrics. */
final class LocalPlatform {
  static final class DurableUrlRepository implements UrlRepository {
    private final Path file; private final Map<String,UrlLink> links = new HashMap<>();
    DurableUrlRepository(Path file) { this.file=file; load(); }
    public synchronized Optional<UrlLink> find(String code){return Optional.ofNullable(links.get(code));}
    public synchronized boolean putIfAbsent(UrlLink link){if(links.containsKey(link.code()))return false;links.put(link.code(),link);save();return true;}
    public synchronized UrlLink incrementVisits(String code){UrlLink old=links.get(code);if(old==null)return null;UrlLink next=new UrlLink(old.code(),old.longUrl(),old.owner(),old.createdAt(),old.visits()+1);links.put(code,next);save();return next;}
    @SuppressWarnings("unchecked") private void load(){try{if(Files.exists(file))try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(file))){Object o=in.readObject();if(o instanceof Map<?,?> m)m.forEach((k,v)->links.put((String)k,(UrlLink)v));}}catch(Exception e){throw new IllegalStateException("cannot load durable link store",e);}}
    private void save(){try{Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");try(ObjectOutputStream out=new ObjectOutputStream(Files.newOutputStream(tmp))){out.writeObject(new HashMap<>(links));}Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new IllegalStateException("cannot persist link store",e);}}
  }
  static final class UrlPolicy {
    void validateCreate(String url) {
      if(url==null||url.length()>2048)throw new IllegalArgumentException("URL is required and must be <= 2048 characters");
      try { var u=new java.net.URI(url); if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||blockedLiteralHost(u.getHost()))throw new IllegalArgumentException("only public, credential-free absolute http(s) URLs are allowed"); }
      catch(java.net.URISyntaxException e){throw new IllegalArgumentException("invalid URL");}
    }
    private boolean blockedLiteralHost(String host){if("localhost".equalsIgnoreCase(host))return true;try{var a=java.net.InetAddress.getByName(host);return a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isSiteLocalAddress()||a.isLinkLocalAddress();}catch(java.net.UnknownHostException ignored){return false;}}
  }
  static final class FixedWindowRateLimiter {
    private final int max; private final Duration window; private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
    FixedWindowRateLimiter(int max,Duration window){this.max=max;this.window=window;}
    boolean allow(String key){Instant now=Instant.now();Bucket b=buckets.compute(key,(k,old)->old==null||now.isAfter(old.start.plus(window))?new Bucket(now,1):new Bucket(old.start,old.count+1));return b.count<=max;}
    private record Bucket(Instant start,int count){}
  }
  static final class Metrics {
    private final ConcurrentHashMap<String,LongAdder> counters=new ConcurrentHashMap<>();
    void count(String name){counters.computeIfAbsent(name,k->new LongAdder()).increment();}
    String prometheus(){StringBuilder s=new StringBuilder();counters.forEach((k,v)->s.append("url_shortener_").append(k).append(" ").append(v.sum()).append('\n'));return s.toString();}
  }
  static final class LinkCache {
    private final int max; private final Duration ttl; private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    LinkCache(int max,Duration ttl){this.max=max;this.ttl=ttl;}
    synchronized UrlLink get(String code){Entry e=entries.get(code);if(e==null||Instant.now().isAfter(e.at.plus(ttl))){entries.remove(code);return null;}return e.link;}
    synchronized void put(UrlLink link){entries.put(link.code(),new Entry(link,Instant.now()));while(entries.size()>max)entries.remove(entries.keySet().iterator().next());}
    private record Entry(UrlLink link,Instant at){}
  }
  static final class IdempotencyStore {
    private final Path file; private final Map<String,Dedup> keys=new HashMap<>();
    IdempotencyStore(Path file){this.file=file;load();}
    synchronized UrlLink create(String key,String fingerprint,Supplier<UrlLink> action){if(key==null||key.isBlank())return action.get();Dedup prior=keys.get(key);if(prior!=null){if(!prior.fingerprint.equals(fingerprint))throw new IllegalArgumentException("idempotency key reused with a different request");return prior.link;}UrlLink link=action.get();keys.put(key,new Dedup(fingerprint,link));save();return link;}
    @SuppressWarnings("unchecked") private void load(){try{if(Files.exists(file))try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(file))){Object value=in.readObject();if(value instanceof Map<?,?> map)map.forEach((k,v)->keys.put((String)k,(Dedup)v));}}catch(Exception e){throw new IllegalStateException("cannot load idempotency store",e);}}
    private void save(){try{Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");try(ObjectOutputStream out=new ObjectOutputStream(Files.newOutputStream(tmp))){out.writeObject(new HashMap<>(keys));}Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new IllegalStateException("cannot persist idempotency store",e);}}
    private record Dedup(String fingerprint,UrlLink link) implements Serializable { }
  }
  static final class LocalApproval {
    private final String token=System.getProperty("approval.token","local-demo-token");
    boolean permitted(String approver,String presented){return approver!=null&&!approver.isBlank()&&java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),Optional.ofNullable(presented).orElse("").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  }
  enum Role { USER, REVIEWER, RELEASE_MANAGER, ADMIN }
  record Principal(String subject,Set<Role> roles) { boolean has(Role role){return roles.contains(Role.ADMIN)||roles.contains(role);} }
  static final class Auth {
    private final Map<String,Principal> keys=Map.of(System.getProperty("auth.user.key","local-user-key"),new Principal("alice",Set.of(Role.USER)),System.getProperty("auth.reviewer.key","local-reviewer-key"),new Principal("riley",Set.of(Role.REVIEWER)),System.getProperty("auth.release.key","local-release-key"),new Principal("reese",Set.of(Role.RELEASE_MANAGER)),System.getProperty("auth.admin.key","local-admin-key"),new Principal("admin",Set.of(Role.ADMIN)));
    Principal authenticate(String key){if(key==null||key.isBlank())throw new AccessDenied(401,"authentication_required");Principal p=keys.get(key);if(p==null)throw new AccessDenied(401,"authentication_required");return p;}
    void require(Principal p,Role role){if(!p.has(role))throw new AccessDenied(403,"insufficient_role");}
    void requireOwnerOrReview(Principal p,UrlLink link){if(p.has(Role.REVIEWER)||p.subject().equals(link.owner()))return;throw new AccessDenied(403,"not_link_owner");}
  }
  static final class AccessDenied extends RuntimeException { final int status; AccessDenied(int status,String code){super(code);this.status=status;} }
}
