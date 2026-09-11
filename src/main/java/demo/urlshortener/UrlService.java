package demo.urlshortener;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

record UrlLink(String code, String longUrl, String owner, Instant createdAt, long visits) implements java.io.Serializable { }
interface UrlRepository { Optional<UrlLink> find(String code); boolean putIfAbsent(UrlLink link); UrlLink incrementVisits(String code); }
final class InMemoryUrlRepository implements UrlRepository { private final java.util.concurrent.ConcurrentHashMap<String,UrlLink> data=new java.util.concurrent.ConcurrentHashMap<>(); public Optional<UrlLink> find(String c){return Optional.ofNullable(data.get(c));} public boolean putIfAbsent(UrlLink l){return data.putIfAbsent(l.code(),l)==null;} public UrlLink incrementVisits(String c){return data.computeIfPresent(c,(k,v)->new UrlLink(v.code(),v.longUrl(),v.owner(),v.createdAt(),v.visits()+1));} }
final class ShortCodeGenerator { private final AtomicLong sequence=new AtomicLong(1000); String next(){return Long.toUnsignedString(sequence.incrementAndGet(),36);} }
final class UrlService {
  private final UrlRepository repo; private final ShortCodeGenerator codes; private final LocalPlatform.LinkCache cache;
  UrlService(UrlRepository repo, ShortCodeGenerator codes){this(repo,codes,null);}
  UrlService(UrlRepository repo, ShortCodeGenerator codes, LocalPlatform.LinkCache cache){this.repo=repo;this.codes=codes;this.cache=cache;}
  UrlLink shorten(String longUrl) { return shorten(longUrl,"legacy"); }
  UrlLink shorten(String longUrl,String owner) { validate(longUrl); for(int i=0;i<3;i++){UrlLink x=new UrlLink(codes.next(),longUrl,owner,Instant.now(),0);if(repo.putIfAbsent(x)){put(x);return x;}}throw new IllegalStateException("code allocation exhausted"); }
  UrlLink resolve(String code){UrlLink x=repo.incrementVisits(code);if(x==null)throw new IllegalArgumentException("unknown code");put(x);return x;}
  UrlLink analytics(String code){UrlLink cached=cache==null?null:cache.get(code);if(cached!=null)return cached;UrlLink found=repo.find(code).orElseThrow(()->new IllegalArgumentException("unknown code"));put(found);return found;}
  private void put(UrlLink link){if(cache!=null)cache.put(link);}
  private void validate(String raw){try{URI u=URI.create(raw);if(!("http".equals(u.getScheme())||"https".equals(u.getScheme()))||u.getHost()==null)throw new IllegalArgumentException("url must be absolute http(s)");}catch(IllegalArgumentException e){throw new IllegalArgumentException("url must be absolute http(s)");}}
}
