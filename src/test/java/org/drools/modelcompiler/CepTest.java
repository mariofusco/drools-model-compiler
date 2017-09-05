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

import java.util.concurrent.TimeUnit;

import org.drools.core.ClockType;
import org.drools.core.impl.KnowledgeBaseFactory;
import org.drools.model.Index.ConstraintType;
import org.drools.model.Model;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.model.impl.ModelImpl;
import org.drools.modelcompiler.builder.KieBaseBuilder;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.time.SessionPseudoClock;
import org.kie.internal.utils.KieHelper;

import static org.drools.model.DSL.*;
import static org.junit.Assert.assertEquals;

public class CepTest {

    @Test
    public void testAfter() throws Exception {
        String drl =
                "import " + StockTick.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "    $a : StockTick( company == \"DROO\" )\n" +
                "    $b : StockTick( company == \"ACME\", this after[5,8] $a )\n" +
                "then\n" +
                "  System.out.println(\"fired\");\n" +
                "end\n";

        KieHelper helper = new KieHelper();
        helper.addContent( drl, ResourceType.DRL );
        KieBase kbase = helper.build( EventProcessingOption.STREAM );

        KieSessionConfiguration sessionConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        sessionConfig.setOption( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );
        KieSession ksession = kbase.newKieSession( sessionConfig, null );

        SessionPseudoClock clock = ksession.getSessionClock();

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 6, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(1, ksession.fireAllRules());

        clock.advanceTime( 4, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(0, ksession.fireAllRules());
    }

    @Test
    public void testAfterWithModel() throws Exception {
        Variable<StockTick> drooV = variableOf( type( StockTick.class ) );
        Variable<StockTick> acmeV = variableOf( type( StockTick.class ) );

        Rule rule = rule( "after" )
                .view(
                        expr("exprA", drooV, s -> s.getCompany().equals("DROO"))
                                .indexedBy( String.class, ConstraintType.EQUAL, StockTick::getCompany, "DROO" )
                                .reactOn( "company" ),
                        expr("exprB", acmeV, s -> s.getCompany().equals("ACME"))
                                .indexedBy( String.class, ConstraintType.EQUAL, StockTick::getCompany, "ACME" )
                                .reactOn( "company" ),
                        expr("exprC", acmeV, drooV, after(5,8))
                     )
                .then(execute(() -> System.out.println("fired")));

        Model model = new ModelImpl().addRule( rule );
        KieBase kbase = KieBaseBuilder.createKieBaseFromModel( model, EventProcessingOption.STREAM );

        KieSessionConfiguration sessionConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        sessionConfig.setOption( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );
        KieSession ksession = kbase.newKieSession( sessionConfig, null );

        SessionPseudoClock clock = ksession.getSessionClock();

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 6, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(1, ksession.fireAllRules());

        clock.advanceTime( 4, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        assertEquals(0, ksession.fireAllRules());
    }

    @Test
    public void testNotAfter() throws Exception {
        String drl =
                "import " + StockTick.class.getCanonicalName() + ";" +
                "rule R when\n" +
                "    $a : StockTick( company == \"DROO\" )\n" +
                "    not( StockTick( company == \"ACME\", this after[5,8] $a ) )\n" +
                "then\n" +
                "  System.out.println(\"fired\");\n" +
                "end\n";

        KieHelper helper = new KieHelper();
        helper.addContent( drl, ResourceType.DRL );
        KieBase kbase = helper.build( EventProcessingOption.STREAM );

        KieSessionConfiguration sessionConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        sessionConfig.setOption( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );
        KieSession ksession = kbase.newKieSession( sessionConfig, null );

        SessionPseudoClock clock = ksession.getSessionClock();

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 6, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        clock.advanceTime( 10, TimeUnit.MILLISECONDS );
        assertEquals(0, ksession.fireAllRules());

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 3, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        clock.advanceTime( 10, TimeUnit.MILLISECONDS );
        assertEquals(1, ksession.fireAllRules());
    }

    @Test
    public void testNotAfterWithModel() throws Exception {
        Variable<StockTick> drooV = variableOf( type( StockTick.class ) );
        Variable<StockTick> acmeV = variableOf( type( StockTick.class ) );

        Rule rule = rule( "after" )
                .view(
                        expr("exprA", drooV, s -> s.getCompany().equals("DROO"))
                                .indexedBy( String.class, ConstraintType.EQUAL, StockTick::getCompany, "DROO" )
                                .reactOn( "company" ),
                        not( expr("exprB", acmeV, s -> s.getCompany().equals("ACME"))
                                .indexedBy( String.class, ConstraintType.EQUAL, StockTick::getCompany, "ACME" )
                                .reactOn( "company" ),
                             expr("exprC", acmeV, drooV, after(5,8)) )
                     )
                .then(execute(() -> System.out.println("fired")));

        Model model = new ModelImpl().addRule( rule );
        KieBase kbase = KieBaseBuilder.createKieBaseFromModel( model, EventProcessingOption.STREAM );

        KieSessionConfiguration sessionConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration();
        sessionConfig.setOption( ClockTypeOption.get( ClockType.PSEUDO_CLOCK.getId() ) );
        KieSession ksession = kbase.newKieSession( sessionConfig, null );

        SessionPseudoClock clock = ksession.getSessionClock();

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 6, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        clock.advanceTime( 10, TimeUnit.MILLISECONDS );
        assertEquals(0, ksession.fireAllRules());

        ksession.insert( new StockTick("DROO") );
        clock.advanceTime( 3, TimeUnit.MILLISECONDS );
        ksession.insert( new StockTick("ACME") );

        clock.advanceTime( 10, TimeUnit.MILLISECONDS );
        assertEquals(1, ksession.fireAllRules());
    }
}
