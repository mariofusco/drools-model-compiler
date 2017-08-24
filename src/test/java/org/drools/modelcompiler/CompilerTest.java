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

import java.util.List;

import org.drools.compiler.kie.builder.impl.KieBuilderImpl;
import org.drools.modelcompiler.builder.CanonicalModelKieProject;
import org.junit.Ignore;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieSession;

import static org.junit.Assert.fail;

public class CompilerTest {

    @Test
    public void testBeta() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $p1 : Person(name == \"Mark\")\n" +
                "  $p2 : Person(name != \"Mark\", age > $p1.age)\n" +
                "then\n" +
                "  System.out.println($p2.getName() + \" is older than \" + $p1.getName());\n" +
                "end";

        KieSession ksession = getKieSession( str );

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();
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
        return getKieSession( str, false );
    }

    private KieSession getKieSession(String str, boolean useCanonicalModel) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem().write( "src/main/resources/r1.drl", str );
        KieBuilder kieBuilder = useCanonicalModel ?
                                ( (KieBuilderImpl) ks.newKieBuilder( kfs ) ).buildAll( CanonicalModelKieProject::new ) :
                                ks.newKieBuilder( kfs ).buildAll();
        List<Message> messages = kieBuilder.getResults().getMessages();
        if (!messages.isEmpty()) {
            fail(messages.toString());
        }
        return ks.newKieContainer(ks.getRepository().getDefaultReleaseId()).newKieSession();
    }

    @Test
    @Ignore("TODO: implement parsing of non Java expression in drlx parser")
    public void testInlineCast() {
        String str =
                "import " + Person.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "  $o : Object( this#Person.name == \"Mark\" )\n" +
                "then\n" +
                "  System.out.println(\"Found: \" + $o);\n" +
                "end";

        KieSession ksession = getKieSession( str, true );

        ksession.insert( "Mark" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();
    }
}
