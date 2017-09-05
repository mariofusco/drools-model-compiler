/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieBuilderImpl;
import org.drools.core.ClockType;
import org.drools.core.reteoo.AlphaNode;
import org.drools.modelcompiler.builder.CanonicalModelKieProject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.builder.model.KieSessionModel;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.time.SessionPseudoClock;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class CompilerTest {
    
    public static enum RUN_TYPE {
        USE_CANONICAL_MODEL,
        STANDARD_FROM_DRL;
    }
    
    @Parameters(name = "{0}")
    public static Object[] params() {
        return new Object[]{
                            RUN_TYPE.STANDARD_FROM_DRL,
                            RUN_TYPE.USE_CANONICAL_MODEL
        };
    }

    private final RUN_TYPE testRunType;
    
    public CompilerTest(RUN_TYPE testRunType) {
        this.testRunType = testRunType;
    }

    public static class Result {
        private Object value;
        
        public Result() {
            // empty constructor.
        }
        
        public Result(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return value;
        }

        public void setValue( Object value ) {
            this.value = value;
        }
    }

    @Test
    public void testBeta() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $r : Result()\n" +
                "  $p1 : Person(name == \"Mark\")\n" +
                "  $p2 : Person(name != \"Mark\", age > $p1.age)\n" +
                "then\n" +
                "  $r.setValue($p2.getName() + \" is older than \" + $p1.getName());\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Result result = new Result();
        ksession.insert(result);

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        assertEquals( "Mario is older than Mark", result.getValue() );
    }
    
    @Test
    public void testShareAlpha() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $r : java.util.Set()\n" +
                "  $p1 : Person(name == \"Edson\")\n" +
                "  $p2 : Person(name != \"Edson\", age > $p1.age)\n" +
                "  $p3 : Person(name != \"Edson\", age > $p1.age, this != $p2)\n" +
                "then\n" +
                "  $r.add($p2);\n" +
                "  $r.add($p3);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Set result = new HashSet<>();
        ksession.insert(result);

        Person mark = new Person("Mark", 37);
        Person edson = new Person("Edson", 35);
        Person mario = new Person("Mario", 40);

        ksession.insert(mark);
        ksession.insert(edson);
        ksession.insert(mario);
        ksession.fireAllRules();

        assertTrue(result.contains(mark));
        assertTrue(result.contains(mario));
        
        // Alpha node "name != Edson" should be shared between 3rd and 4th pattern.
        // therefore alpha nodes should be a total of 2: name == Edson, name != Edson
        assertEquals(2, ReteDumper.dumpRete(ksession).stream().filter(AlphaNode.class::isInstance).count());
    }

    @Test
    public void test3Patterns() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $mark : Person(name == \"Mark\")\n" +
                "  $p : Person(age > $mark.age)\n" +
                "  $s: String(this == $p.name)\n" +
                "then\n" +
                "  System.out.println(\"Found: \" + $s);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert( "Mario" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();
    }

    @Test
    public void testOr() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person(name == \"Mark\") or\n" +
                "  ( $mark : Person(name == \"Mark\")\n" +
                "    and\n" +
                "    $p : Person(age > $mark.age) )\n" +
                "  $s: String(this == $p.name)\n" +
                "then\n" +
                "  System.out.println(\"Found: \" + $s);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert( "Mario" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();
    }

    private KieSession getKieSession(String str) {
        return getKieSession(str, null);
    }

    private KieSession getKieSession(String str, KieModuleModel model) {
        KieServices ks = KieServices.Factory.get();
        
        ReleaseId releaseId = ks.newReleaseId( "org.kie", "kjar-test-" + UUID.randomUUID(), "1.0" );
        
        KieRepository repo = ks.getRepository();
        repo.removeKieModule( releaseId );
        
        KieFileSystem kfs = ks.newKieFileSystem();
        if (model != null) {
            kfs.writeKModuleXML( model.toXML() );
        }
        kfs.writePomXML(KJARUtils.getPom(releaseId));
        kfs.write( "src/main/resources/r1.drl", str );
// This is actually taken from classloader of test (?) - or anyway it must, because the test are instantiating directly Person.
//        String javaSrc = Person.class.getCanonicalName().replace( '.', File.separatorChar ) + ".java";
//        Resource javaResource = ks.getResources().newFileSystemResource( "src/test/java/" + javaSrc );
//        kfs.write( "src/main/java/" + javaSrc, javaResource );

        KieBuilder kieBuilder = (testRunType == RUN_TYPE.USE_CANONICAL_MODEL) ?
                                ( (KieBuilderImpl) ks.newKieBuilder( kfs ) ).buildAll( CanonicalModelKieProject::new ) :
                                ks.newKieBuilder( kfs ).buildAll();
        List<Message> messages = kieBuilder.getResults().getMessages();
        if (!messages.isEmpty()) {
            fail(messages.toString());
        }
        
        if (testRunType == RUN_TYPE.STANDARD_FROM_DRL) { 
            return ks.newKieContainer(releaseId).newKieSession();
        } else {
            InternalKieModule kieModule = (InternalKieModule) kieBuilder.getKieModule();
            File kjarFile = TestFileUtils.bytesToTempKJARFile( releaseId, kieModule.getBytes(), ".jar" );
            KieModule zipKieModule = new CanonicalKieModule( releaseId, model != null ? model : getDefaultKieModuleModel( ks ), kjarFile );
            repo.addKieModule( zipKieModule );
            
            KieContainer kieContainer = ks.newKieContainer( releaseId );
            KieSession kieSession = kieContainer.newKieSession();
            
            return kieSession;
        }
    }

    private KieModuleModel getDefaultKieModuleModel(KieServices ks) {
        KieModuleModel kproj = ks.newKieModuleModel();
        KieBaseModel kieBaseModel1 = kproj.newKieBaseModel( "kbase" ).setDefault( true );
        KieSessionModel ksession1 = kieBaseModel1.newKieSessionModel( "ksession" ).setDefault( true );
        return kproj;

    }

    @Test
    public void testInlineCast() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $r : Result()\n" +
                "  $o : Object( this#Person.name == \"Mark\" )\n" +
                "then\n" +
                "  $r.setValue(\"Found: \" + $o);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Result result = new Result();
        ksession.insert(result);

        ksession.insert( "Mark" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        assertEquals( "Found: Mark", result.getValue() );
    }

    @Test
    public void testNullSafeDereferncing() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $r : Result()\n" +
                "  $p : Person( name!.length == 4 )\n" +
                "then\n" +
                "  $r.setValue(\"Found: \" + $p);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Result result = new Result();
        ksession.insert(result);

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.insert(new Person(null, 40));
        ksession.fireAllRules();

        assertEquals( "Found: Mark", result.getValue() );
    }
   
    public static <T> List<T> getObjects(KieSession ksession, Class<T> clazz) {
        return (List<T>) ksession.getObjects(clazz::isInstance).stream().collect(Collectors.toList());
    }
    
    @Test
    public void testSimpleInsert() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person( name.length == 4 )\n" +
                "then\n" +
                "  Result r = new Result($p.getName());" +
                "  insert(r);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        List<Result> results = getObjects(ksession, Result.class);
        assertEquals(1, results.size());
        assertEquals("Mark", results.get(0).getValue());
    }
    
    @Test
    public void testSimpleDelete() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person( name.length == 4 )\n" +
                "then\n" +
                "  Result r = new Result($p.getName());" +
                "  insert(r);\n" +
                "  delete($p);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        List<Result> results = getObjects(ksession, Result.class);
        assertEquals(1, results.size());
        assertEquals("Mark", results.get(0).getValue());
        assertEquals(1, getObjects(ksession, Person.class).size());
    }
    
    @Test
    public void testSimpleInsertDeleteExplicitScope() {
        String str =
                "import " + Result.class.getCanonicalName() + ";" +
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person( name.length == 4 )\n" +
                "then\n" +
                "  Result r = new Result($p.getName());" +
                "  drools.insert(r);\n" +
                "  drools.delete($p);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        List<Result> results = getObjects(ksession, Result.class);
        assertEquals(1, results.size());
        assertEquals("Mark", results.get(0).getValue());
        assertEquals(1, getObjects(ksession, Person.class).size());
    }

    @Test
    public void testSimpleUpdate() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person( name.length == 4 )\n" +
                "then\n" +
                "  $p.setAge($p.getAge()+1);" +
                "  update($p);\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Person mark = new Person("Mark", 37);
        Person mario = new Person("Mario", 40);

        ksession.insert(mark);
        ksession.insert(mario);
        ksession.fireAllRules();

        assertEquals( 38, mark.getAge() );
        assertEquals( 40, mario.getAge() );
    }

    @Test
    public void testSimpleModify() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p : Person( name.length == 4 )\n" +
                "then\n" +
                "  modify($p) { setAge($p.getAge()+1) }\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Person mark = new Person("Mark", 37);
        Person mario = new Person("Mario", 40);

        ksession.insert(mark);
        ksession.insert(mario);
        ksession.fireAllRules();

        assertEquals( 38, mark.getAge() );
        assertEquals( 40, mario.getAge() );
    }

    @Test
    public void testAfter() throws Exception {
        String str =
                "import " + StockTick.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "    $a : StockTick( company == \"DROO\" )\n" +
                "    $b : StockTick( company == \"ACME\", this after[5s,8s] $a )\n" +
                "then\n" +
                "  System.out.println(\"fired\");\n" +
                "end\n";

        KieModuleModel kproj = KieServices.get().newKieModuleModel();
        kproj.newKieBaseModel( "kb" )
             .setDefault( true )
             .setEventProcessingMode( EventProcessingOption.STREAM )
             .newKieSessionModel( "ks" )
             .setDefault( true ).setClockType( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );

        KieSession ksession = getKieSession( str, kproj );
        SessionPseudoClock clock = ksession.getSessionClock();

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 6, TimeUnit.SECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(1, ksession.fireAllRules());

        clock.advanceTime( 4, TimeUnit.SECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(0, ksession.fireAllRules());
    }
    
    @Test
    public void testNot() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "import " + Result.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  not( Person( name.length == 4 ) )\n" +
                "then\n" +
                "  insert(new Result(\"ok\"));\n" +
                "end";

        KieSession ksession = getKieSession( str );

        Person mario = new Person("Mario", 40);

        ksession.insert(mario);
        ksession.fireAllRules();

        List<Result> results = getObjects(ksession, Result.class);
        assertEquals(1, results.size());
        assertEquals("ok", results.get(0).getValue());
    }
}
