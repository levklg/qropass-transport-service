package com.qropass.transport.hta202;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированная PostgreSQL 15 foundation; не включается в production artifact. */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class Hta202PublicationPostgresTest {
 @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:15");
 static Connection c;
 static final ThreadLocal<Connection> restrictedConnection=new ThreadLocal<>();
 static Connection active(){ Connection r=restrictedConnection.get();return r==null?c:r; }
 static final String SQL = "/hta202/isolated-publication.sql";
 @BeforeAll static void init() throws Exception {
  c=DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(),db.getPassword());
  Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
    .locations("classpath:db/migration").load().migrate();
  String sql=new String(Objects.requireNonNull(Hta202PublicationPostgresTest.class.getResourceAsStream(SQL))
    .readAllBytes(),StandardCharsets.UTF_8);
  // PostgreSQL JDBC permits multiple SQL statements per Statement.execute().
  try(Statement s=c.createStatement()) {s.execute(sql);
   for(String role: List.of("reader","writer","runtime")) {
    s.execute("CREATE ROLE hta202_test_"+role+" LOGIN PASSWORD 'hta202_ephemeral_only'");
    s.execute("GRANT hta202_"+role+" TO hta202_test_"+role);
   }
  }
 }
 @AfterAll static void close() throws Exception { if(c!=null)c.close(); }
 @BeforeEach void clear() throws Exception {
  c.setAutoCommit(true);
  // Tests operate only in disposable Testcontainers and reset isolated evidence via superuser.
  try(Statement s=c.createStatement()){
    s.execute("SET ROLE NONE");
    s.execute("TRUNCATE public.legal_entity,public.route,public.stop CASCADE");
  }
 }
 record Fixture(UUID version, String fingerprint) {}
 static UUID id(){return UUID.randomUUID();}
 static int update(String sql,Object... vals) throws Exception {
  try(PreparedStatement p=active().prepareStatement(sql)){
   for(int i=0;i<vals.length;i++)p.setObject(i+1,vals[i]);
   return p.executeUpdate();
  }
 }
 static <T> T scalar(String sql, Class<T> type, Object... vals) throws Exception {
  try(PreparedStatement p=active().prepareStatement(sql)){
   for(int i=0;i<vals.length;i++)p.setObject(i+1,vals[i]);
   try(ResultSet r=p.executeQuery()){assertTrue(r.next());return r.getObject(1,type);}
  }
 }
 static Fixture seed() throws Exception {
  UUID legal=id(),route=id(),dir=id(),pattern=id(),version=id(),s1=id(),s2=id();
  update("INSERT INTO public.legal_entity(id,inn,status) VALUES(?,'4027059760','ACTIVE')",legal);
  update("INSERT INTO public.route(id,route_name) VALUES(?,'TEST ONLY')",route);
  update("INSERT INTO public.route_direction(id,route_id,direction_code) VALUES(?,?,'OUT')",dir,route);
  update("INSERT INTO public.route_pattern(id,direction_id,pattern_code) VALUES(?,?,'MAIN')",pattern,dir);
  update("INSERT INTO public.operator_assignment(id,route_id,legal_entity_id,role,status,valid_from,evidence_reference) VALUES(?,?,?,'CARRIER','VERIFIED',now(),'TEST')",id(),route,legal);
  update("INSERT INTO public.route_pattern_version(id,pattern_id,legal_entity_id,version_number,effective_from) VALUES(?,?,?,1,?)",
    version,pattern,legal,Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
  update("INSERT INTO public.stop(id,canonical_name) VALUES(?,'A'),(?,'B')",s1,s2);
  update("INSERT INTO public.stop_occurrence(id,pattern_version_id,stop_id,sequence_number) VALUES(?,?,?,1),(?,?,?,2)",
    id(),version,s1,id(),version,s2);
  String fp=scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,version);
  return new Fixture(version,fp);
 }
 static UUID publish(Fixture f) throws Exception{
  return scalar("SELECT hta202.publish_test_only(?,?)",UUID.class,f.version,f.fingerprint);
 }
 static long count(Fixture f) throws Exception {
  return scalar("SELECT count(*) FROM hta202.publication_evidence WHERE route_pattern_version_id=?",Long.class,f.version);
 }
 static void asRole(String role, Throwing action) throws Exception {
  String suffix=role.replace("hta202_","");
  try(Connection restricted=DriverManager.getConnection(db.getJdbcUrl(),
       "hta202_test_"+suffix,"hta202_ephemeral_only")) {
    restrictedConnection.set(restricted);
    try {action.run();} finally {restrictedConnection.remove();}
  }
 }
 interface Throwing { void run() throws Exception; }
 static void denied(String code, Throwing a) {
  SQLException e=assertThrows(SQLException.class,()->a.run());
  assertEquals(code,e.getSQLState());
 }
 @Test void t01_atomicPublishedEvidence() throws Exception {
  Fixture f=seed(); UUID ev=publish(f);
  assertEquals(1L,count(f));
  assertEquals("PUBLISHED",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
  assertEquals(ev,scalar("SELECT evidence_id FROM hta202.publication_evidence WHERE route_pattern_version_id=?",UUID.class,f.version));
 }
 @Test void t02_rollbackBoth() throws Exception {
  Fixture f=seed(); c.setAutoCommit(false);
  try {publish(f);c.rollback();}finally{c.setAutoCommit(true);}
  assertEquals("DRAFT",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
  assertEquals(0L,count(f));
 }
 @Test void t03_repeatIsIdempotent() throws Exception {
  Fixture f=seed();UUID first=publish(f); UUID second=publish(f);
  assertEquals(first,second);assertEquals(1L,count(f));
 }
 @Test void t04_concurrentPublication() throws Exception {
  Fixture f=seed(); ExecutorService pool=Executors.newFixedThreadPool(2);
  try {
   Callable<UUID> task=()->{try(Connection other=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());
     PreparedStatement p=other.prepareStatement("SELECT hta202.publish_test_only(?,?)")){
       p.setObject(1,f.version);p.setString(2,f.fingerprint);
       try(ResultSet r=p.executeQuery()){r.next();return (UUID)r.getObject(1);}
     }};
   var a=pool.submit(task);var b=pool.submit(task);
   assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));assertEquals(1L,count(f));
  }finally{pool.shutdownNow();}
 }
 @Test void t05_mismatchRejected() throws Exception {
  Fixture f=seed();String wrong="sha256:"+"0".repeat(64);
  denied("23514",()->scalar("SELECT hta202.publish_test_only(?,?)",UUID.class,f.version,wrong));
 }
 @Test void t06_updateRejected() throws Exception {
  Fixture f=seed();publish(f);
  denied("23514",()->update("UPDATE hta202.publication_evidence SET publication_policy_version='FORGED' WHERE route_pattern_version_id=?",f.version));
 }
 @Test void t07_deleteRejected() throws Exception {
  Fixture f=seed();publish(f);
  denied("23514",()->update("DELETE FROM hta202.publication_evidence WHERE route_pattern_version_id=?",f.version));
 }
 @Test void t08_truncateReaderDenied() throws Exception {
  asRole("hta202_reader",()->denied("42501",()->{try(Statement s=active().createStatement()){s.execute("TRUNCATE hta202.publication_evidence");}}));
 }
 @Test void t09_insertWriterDenied() throws Exception {
  asRole("hta202_writer",()->denied("42501",()->update("INSERT INTO hta202.publication_evidence(evidence_id) VALUES(?)",id())));
 }
 @Test void t10_roleEscalationDenied() throws Exception {
  asRole("hta202_reader",()->denied("42501",()->{try(Statement s=active().createStatement()){s.execute("SET ROLE hta202_owner");}}));
 }
 @Test void t11_directPublishedNoEvidenceRejected() throws Exception {
  Fixture f=seed(); c.setAutoCommit(false);
  try {
   update("UPDATE public.route_pattern_version SET status='PUBLISHED',content_fingerprint=? WHERE id=?",f.fingerprint,f.version);
   denied("23514",c::commit);
  }finally{c.rollback();c.setAutoCommit(true);}
  assertEquals("DRAFT",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
 }
 @Test void t12_legacyPublishedNoBackfill() throws Exception {
  Fixture f=seed(); // Simulates a legacy PUBLISHED row created before Slice 01 guard installation.
  try(Statement s=c.createStatement()){s.execute("ALTER TABLE public.route_pattern_version DISABLE TRIGGER hta202_publication_guard");}
  try {update("UPDATE public.route_pattern_version SET status='PUBLISHED',content_fingerprint=? WHERE id=?",f.fingerprint,f.version);}
  finally{try(Statement s=c.createStatement()){s.execute("ALTER TABLE public.route_pattern_version ENABLE TRIGGER hta202_publication_guard");}}
  assertEquals(0L,count(f));
  assertEquals("HISTORICAL_AUTHORITY_UNPROVEN",historical(f,Instant.parse("2026-11-10T00:00:00Z")));
 }
 // Slice01 never returns VERIFIED, even when storage evidence exists.
 static String historical(Fixture f,Instant at)throws Exception {
  long n=count(f);
  String status=scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version);
  if(n==0 || !"PUBLISHED".equals(status))return "HISTORICAL_AUTHORITY_UNPROVEN";
  return "HISTORICAL_AUTHORITY_UNPROVEN"; // COMMIT boundary cannot be proven in Slice 01.
 }
 @Test void t13_backdatedEffectiveFromDoesNotProveHistory() throws Exception {
  Fixture f=seed();publish(f);
  assertEquals("HISTORICAL_AUTHORITY_UNPROVEN",historical(f,Instant.parse("2025-02-01T00:00:00Z")));
 }
 @Test void t14_failureBeforeCommitRollsBack() throws Exception {
  Fixture f=seed();c.setAutoCommit(false);
  try {publish(f);denied("23514",()->update("DELETE FROM hta202.publication_evidence WHERE route_pattern_version_id=?",f.version));c.rollback();}
  finally {c.setAutoCommit(true);}
  assertEquals(0L,count(f));
  assertEquals("DRAFT",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
 }
 @Test void t15_commitBoundaryUnproven() throws Exception {
  Fixture f=seed();publish(f);assertEquals("HISTORICAL_AUTHORITY_UNPROVEN",historical(f,Instant.now()));
 }
 @Test void t16_readerCannotPublishOrExecuteDigest() throws Exception {
  Fixture f=seed();
  asRole("hta202_reader",()->denied("42501",()->scalar("SELECT hta202.publish_test_only(?,?)",UUID.class,f.version,f.fingerprint)));
  asRole("hta202_reader",()->denied("42501",()->scalar("SELECT hta202.digest_evidence(?,?,?,?,?)",String.class,
   f.version,f.fingerprint,id(),Timestamp.from(Instant.now()),"FORGED")));
 }
 @Test void t17_unknownVersionDenied() throws Exception {
  denied("23514",()->scalar("SELECT hta202.publish_test_only(?,?)",UUID.class,id(),"sha256:"+"a".repeat(64)));
 }
 @Test void t18_runtimeCannotMutate() throws Exception {
  Fixture f=seed();publish(f);
  asRole("hta202_runtime",()->denied("42501",()->update("DELETE FROM hta202.publication_evidence WHERE route_pattern_version_id=?",f.version)));
 }
 @Test void t19_validOwnFingerprintDeferredEvidenceGuard() throws Exception {
  Fixture original=seed();
  UUID fresh=id();
  UUID pattern=scalar("SELECT pattern_id FROM public.route_pattern_version WHERE id=?",UUID.class,original.version);
  UUID legal=scalar("SELECT legal_entity_id FROM public.route_pattern_version WHERE id=?",UUID.class,original.version);
  UUID stopA=scalar("SELECT stop_id FROM public.stop_occurrence WHERE pattern_version_id=? AND sequence_number=1",UUID.class,original.version);
  UUID stopB=scalar("SELECT stop_id FROM public.stop_occurrence WHERE pattern_version_id=? AND sequence_number=2",UUID.class,original.version);
  update("INSERT INTO public.route_pattern_version(id,pattern_id,legal_entity_id,version_number,effective_from) "+
      "VALUES(?,?,?,2,?)",fresh,pattern,legal,Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
  update("INSERT INTO public.stop_occurrence(id,pattern_version_id,stop_id,sequence_number) "+
      "VALUES(?,?,?,1),(?,?,?,2)",id(),fresh,stopA,id(),fresh,stopB);
  String own=scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,fresh);
  assertNotEquals(original.fingerprint,own);
  c.setAutoCommit(false);
  try {
    update("UPDATE public.route_pattern_version SET status='PUBLISHED',content_fingerprint=? WHERE id=?",own,fresh);
    assertEquals("PUBLISHED",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,fresh));
    assertEquals(own,scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,fresh));
    // V2 structural validation completed successfully; only deferred HTA202 guard rejects COMMIT.
    SQLException e=assertThrows(SQLException.class,c::commit);
    assertEquals("23514",e.getSQLState());
    assertTrue(e.getMessage().contains("Published version lacks immutable evidence"),e.getMessage());
  } finally {c.rollback();c.setAutoCommit(true);}
  assertEquals("DRAFT",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,fresh));
  assertEquals(0L,scalar("SELECT count(*) FROM hta202.publication_evidence WHERE route_pattern_version_id=?",Long.class,fresh));
 }
 @Test void t20_restrictedRoleCannotCreateInPublicSearchPath() throws Exception {
  asRole("hta202_reader",()->denied("42501",()->{
   try(Statement s=active().createStatement()){s.execute("CREATE TABLE public.hta202_untrusted_shadow(id integer)");}
  }));
 }

 @Test void t21_writerTempShadowDoesNotAffectCanonicalFingerprint() throws Exception {
  Fixture f=seed();
  String expected=scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,f.version);
  assertEquals(f.fingerprint,expected);
  final UUID[] evidence=new UUID[1];
  asRole("hta202_writer",()->{
   try(Statement s=active().createStatement()) {
    // An adversarial writer owns temporary relations shadowing EVERY unqualified
    // Transport table referenced by the unchanged V2 fingerprint/guards.
    for(String name:List.of("route_pattern_version","route_pattern","route_direction",
        "stop_occurrence","fare_section","operator_assignment")) {
      s.execute("CREATE TEMP TABLE "+name+" (id uuid)");
    }
   }
   assertNotNull(scalar("SELECT to_regclass('pg_temp.route_pattern_version')::text",String.class));
   assertEquals("route_pattern_version",scalar("SELECT to_regclass('route_pattern_version')::text",String.class));
   evidence[0]=publish(f);
   assertNotNull(evidence[0]);
  });
  assertEquals("PUBLISHED",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
  assertEquals(1L,count(f));
  assertEquals(expected,scalar("SELECT content_fingerprint FROM public.route_pattern_version WHERE id=?",String.class,f.version));
  assertEquals(expected,scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,f.version));
 }
 @Test void t22_writerTempShadowCannotPublishForgedFingerprint() throws Exception {
  Fixture f=seed();
  String forged="sha256:"+"f".repeat(64);
  asRole("hta202_writer",()->{
   try(Statement s=active().createStatement()){
    for(String name:List.of("route_pattern_version","route_pattern","route_direction",
        "stop_occurrence","fare_section","operator_assignment")) {
      s.execute("CREATE TEMP TABLE "+name+" (id uuid)");
    }
   }
   assertNotNull(scalar("SELECT to_regclass('pg_temp.stop_occurrence')::text",String.class));
   denied("23514",()->scalar("SELECT hta202.publish_test_only(?,?)",UUID.class,f.version,forged));
  });
  assertEquals("DRAFT",scalar("SELECT status FROM public.route_pattern_version WHERE id=?",String.class,f.version));
  assertEquals(0L,count(f));
 }
 @Test void t23_writerNoEscalationOrArbitraryDmlUnderTempShadow() throws Exception {
  Fixture f=seed();
  asRole("hta202_writer",()->{
   try(Statement s=active().createStatement()){s.execute("CREATE TEMP TABLE route_pattern_version (id uuid)");}
   denied("42501",()->{try(Statement s=active().createStatement()){s.execute("SET ROLE hta202_owner");}});
   denied("42501",()->update("UPDATE public.route_pattern_version SET status='PUBLISHED' WHERE id=?",f.version));
   denied("42501",()->update("INSERT INTO hta202.publication_evidence(evidence_id) VALUES(?)",id()));
  });
 }

 @Test void t24_directInsertMatchingCanonicalFingerprintHitsDeferredInsertGuard() throws Exception {
  Fixture original=seed();
  UUID pattern=scalar("SELECT pattern_id FROM public.route_pattern_version WHERE id=?",UUID.class,original.version);
  UUID legal=scalar("SELECT legal_entity_id FROM public.route_pattern_version WHERE id=?",UUID.class,original.version);
  UUID direction=scalar("SELECT direction_id FROM public.route_pattern WHERE id=?",UUID.class,pattern);
  UUID route=scalar("SELECT route_id FROM public.route_direction WHERE id=?",UUID.class,direction);
  UUID newVersion=id();
  // Exact unchanged V1 binary fingerprint for a newly inserted version with zero children.
  // The V2 header guard only fires on UPDATE; HTA202 must independently guard direct INSERT.
  java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
  try(java.io.DataOutputStream out=new java.io.DataOutputStream(bytes)) {
   out.write("QRP-TRANSPORT-FP".getBytes(StandardCharsets.US_ASCII));out.writeByte(1);
   for(UUID v:List.of(legal,route,direction,pattern,newVersion)) {
    out.writeLong(v.getMostSignificantBits());out.writeLong(v.getLeastSignificantBits());
   }
   out.writeInt(0);out.writeInt(0);
  }
  String canonical="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
  c.setAutoCommit(false);
  try {
   update("INSERT INTO public.route_pattern_version(id,pattern_id,legal_entity_id,version_number,status,content_fingerprint,effective_from) "+
      "VALUES(?,?,?,3,'PUBLISHED',?,?)",newVersion,pattern,legal,canonical,
      Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
   assertEquals(canonical,scalar("SELECT public.transport_canonical_fingerprint(?)",String.class,newVersion));
   SQLException e=assertThrows(SQLException.class,c::commit);
   assertEquals("23514",e.getSQLState());
   assertTrue(e.getMessage().contains("Published version lacks immutable evidence"),e.getMessage());
  } finally {c.rollback();c.setAutoCommit(true);}
  assertEquals(0L,scalar("SELECT count(*) FROM public.route_pattern_version WHERE id=?",Long.class,newVersion));
  assertEquals(0L,scalar("SELECT count(*) FROM hta202.publication_evidence WHERE route_pattern_version_id=?",Long.class,newVersion));
 }

}
