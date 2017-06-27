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

import java.util.ArrayList;
import java.util.List;

import org.drools.core.impl.InternalKnowledgeBase;
import org.drools.model.Index.ConstraintType;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.modelcompiler.builder.KieBaseBuilder;
import org.junit.Test;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

import static java.util.Arrays.asList;
import static org.drools.model.DSL.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FlowTest {

    @Test
    public void testBeta() {
        Result result = new Result();

        List<String> list = new ArrayList<>();
        Variable<Person> markV = variableOf( type( Person.class ) );
        Variable<Person> olderV = variableOf( type( Person.class ) );

        Rule rule = rule( "beta" )
                .view(
                        expr("exprA", markV, p -> p.getName().equals("Mark"))
                                .indexedBy( ConstraintType.EQUAL, Person::getName, "Mark" )
                                .reactOn( "name", "age" ), // also react on age, see RuleDescr.lookAheadFieldsOfIdentifier
                        expr("exprB", olderV, p -> !p.getName().equals("Mark"))
                                .reactOn( "name" ),
                        expr("exprC", olderV, markV, (p1, p2) -> p1.getAge() > p2.getAge())
                                .reactOn( "age" )
                     )
                .then(c -> c.on(olderV, markV)
                            .execute((p1, p2) -> result.value = p1.getName() + " is older than " + p2.getName()));

        InternalKnowledgeBase kieBase = KieBaseBuilder.createKieBaseFromModel( () -> asList( rule ) );

        KieSession ksession = kieBase.newKieSession();

        Person mark = new Person("Mark", 37);
        Person edson = new Person("Edson", 35);
        Person mario = new Person("Mario", 40);

        FactHandle markFH = ksession.insert(mark);
        FactHandle edsonFH = ksession.insert(edson);
        FactHandle marioFH = ksession.insert(mario);

        ksession.fireAllRules();
        assertEquals("Mario is older than Mark", result.value);

        result.value = null;
        ksession.delete( marioFH );
        ksession.fireAllRules();
        assertNull(result.value);

        mark.setAge( 34 );
        ksession.update( markFH, mark, "age" );

        ksession.fireAllRules();
        assertEquals("Edson is older than Mark", result.value);
    }

    @Test
    public void test3Patterns() {
        Result result = new Result();

        List<String> list = new ArrayList<>();
        Variable<Person> personV = variableOf( type( Person.class ) );
        Variable<Person> markV = variableOf( type( Person.class ) );
        Variable<String> nameV = variableOf( type( String.class ) );

        Rule rule = rule( "myrule" )
                .view(
                        expr("exprA", markV, p -> p.getName().equals("Mark")),
                        expr("exprB", personV, markV, (p1, p2) -> p1.getAge() > p2.getAge()),
                        expr("exprC", nameV, personV, (s, p) -> s.equals( p.getName() ))
                     )
                .then(c -> c.on(nameV)
                            .execute(s -> result.value = s));

        InternalKnowledgeBase kieBase = KieBaseBuilder.createKieBaseFromModel( () -> asList( rule ) );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert( "Mario" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        assertEquals("Mario", result.value);
    }

    @Test
    public void testOr() {
        Result result = new Result();

        List<String> list = new ArrayList<>();
        Variable<Person> personV = variableOf( type( Person.class ) );
        Variable<Person> markV = variableOf( type( Person.class ) );
        Variable<String> nameV = variableOf( type( String.class ) );

        Rule rule = rule( "or" )
                .view(
                        or(
                            expr("exprA", personV, p -> p.getName().equals("Mark")),
                            and(
                                    expr("exprA", markV, p -> p.getName().equals("Mark")),
                                    expr("exprB", personV, markV, (p1, p2) -> p1.getAge() > p2.getAge())
                               )
                          ),
                        expr("exprC", nameV, personV, (s, p) -> s.equals( p.getName() ))
                     )
                .then(c -> c.on(nameV)
                            .execute(s -> result.value = s));

        InternalKnowledgeBase kieBase = KieBaseBuilder.createKieBaseFromModel( () -> asList( rule ) );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert( "Mario" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        assertEquals("Mario", result.value);
    }

    private static class Result {
        Object value;
    }
}
