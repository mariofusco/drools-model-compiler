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

import org.drools.model.Index.ConstraintType;
import org.drools.model.Model;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.model.impl.ModelImpl;
import org.drools.model.Global;
import org.drools.modelcompiler.builder.KieBaseBuilder;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

import static org.drools.model.DSL.*;
import static org.drools.model.functions.accumulate.Average.avg;
import static org.drools.model.functions.accumulate.Sum.sum;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FlowTest {

    public static class Result {
        private Object value;

        public Object getValue() {
            return value;
        }

        public void setValue( Object value ) {
            this.value = value;
        }
    }

    @Test
    public void testBeta() {
        Result result = new Result();
        Variable<Person> markV = variableOf( type( Person.class ) );
        Variable<Person> olderV = variableOf( type( Person.class ) );

        Rule rule = rule( "beta" )
                .view(
                        expr("exprA", markV, p -> p.getName().equals("Mark"))
                                .indexedBy( String.class, ConstraintType.EQUAL, Person::getName, "Mark" )
                                .reactOn( "name", "age" ), // also react on age, see RuleDescr.lookAheadFieldsOfIdentifier
                        expr("exprB", olderV, p -> !p.getName().equals("Mark"))
                                .indexedBy( String.class, ConstraintType.NOT_EQUAL, Person::getName, "Mark" )
                                .reactOn( "name" ),
                        expr("exprC", olderV, markV, (p1, p2) -> p1.getAge() > p2.getAge())
                                .indexedBy( int.class, ConstraintType.GREATER_THAN, Person::getAge, Person::getAge )
                                .reactOn( "age" )
                     )
                .then(on(olderV, markV)
                            .execute((p1, p2) -> result.value = p1.getName() + " is older than " + p2.getName()));

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

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
        Variable<Person> personV = variableOf( type( Person.class ) );
        Variable<Person> markV = variableOf( type( Person.class ) );
        Variable<String> nameV = variableOf( type( String.class ) );

        Rule rule = rule( "myrule" )
                .view(
                        expr("exprA", markV, p -> p.getName().equals("Mark")),
                        expr("exprB", personV, markV, (p1, p2) -> p1.getAge() > p2.getAge()),
                        expr("exprC", nameV, personV, (s, p) -> s.equals( p.getName() ))
                     )
                .then(on(nameV)
                            .execute(s -> result.value = s));

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

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
                .then(on(nameV)
                            .execute(s -> result.value = s));

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert( "Mario" );
        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));
        ksession.fireAllRules();

        assertEquals("Mario", result.value);
    }

    @Test
    public void testNot() {
        Result result = new Result();
        Variable<Person> oldestV = variableOf( type( Person.class ) );
        Variable<Person> otherV = variableOf( type( Person.class ) );

        Rule rule = rule("not")
                .view(
                        not(otherV, oldestV, (p1, p2) -> p1.getAge() > p2.getAge())
                     )
                .then(on(oldestV)
                            .execute(p -> result.value = "Oldest person is " + p.getName()));

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));

        ksession.fireAllRules();
        assertEquals("Oldest person is Mario", result.value);
    }

    @Test
    public void testAccumulate1() {
        Result result = new Result();
        Variable<Person> person = variableOf( type( Person.class ) );
        Variable<Integer> resultSum = variableOf( type( Integer.class ) );

        Rule rule = rule("accumulate")
                .view(
                        accumulate(expr(person, p -> p.getName().startsWith("M")),
                                   sum(Person::getAge).as(resultSum))
                     )
                .then( on(resultSum).execute(sum -> result.value = "total = " + sum) );

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));

        ksession.fireAllRules();
        assertEquals("total = 77", result.value);
    }

    @Test
    public void testAccumulate2() {
        Result result = new Result();
        Variable<Person> person = variableOf( type( Person.class ) );
        Variable<Integer> resultSum = variableOf( type( Integer.class ) );
        Variable<Double> resultAvg = variableOf( type( Double.class ) );

        Rule rule = rule("accumulate")
                .view(
                        accumulate(expr(person, p -> p.getName().startsWith("M")),
                                   sum(Person::getAge).as(resultSum),
                                   avg(Person::getAge).as(resultAvg))
                     )
                .then(
                        on(resultSum, resultAvg)
                                .execute((sum, avg) -> result.value = "total = " + sum + "; average = " + avg)
                     );

        Model model = new ModelImpl().addRule( rule );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        ksession.insert(new Person("Mark", 37));
        ksession.insert(new Person("Edson", 35));
        ksession.insert(new Person("Mario", 40));

        ksession.fireAllRules();
        assertEquals("total = 77; average = 38.5", result.value);
    }

    @Test
    public void testGlobalInConsequence() {
        Variable<Person> markV = variableOf( type( Person.class ) );
        Global<Result> resultG = globalOf( type( Result.class ), "org.mypkg" );

        Rule rule = rule( "org.mypkg", "global" )
                .view(
                        expr("exprA", markV, p -> p.getName().equals("Mark"))
                                .indexedBy( String.class, ConstraintType.EQUAL, Person::getName, "Mark" )
                                .reactOn( "name" )
                     )
                .then(on(markV, resultG)
                              .execute((p, r) -> r.setValue( p.getName() + " is " + p.getAge() ) ) );

        Model model = new ModelImpl().addRule( rule ).addGlobal( resultG );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        Result result = new Result();
        ksession.setGlobal( resultG.getName(), result );

        Person mark = new Person("Mark", 37);
        Person edson = new Person("Edson", 35);
        Person mario = new Person("Mario", 40);

        FactHandle markFH = ksession.insert(mark);
        FactHandle edsonFH = ksession.insert(edson);
        FactHandle marioFH = ksession.insert(mario);

        ksession.fireAllRules();
        assertEquals("Mark is 37", result.value);
    }

    @Test
    public void testGlobalInConstraint() {
        Variable<Person> markV = variableOf( type( Person.class ) );
        Global<Result> resultG = globalOf( type( Result.class ), "org.mypkg" );
        Global<String> nameG = globalOf( type( String.class ), "org.mypkg" );

        Rule rule = rule( "org.mypkg", "global" )
                .view(
                        expr("exprA", markV, nameG, (p, n) -> p.getName().equals(n))
                                .reactOn( "name" )
                     )
                .then(on(markV, resultG)
                              .execute((p, r) -> r.setValue( p.getName() + " is " + p.getAge() ) ) );

        Model model = new ModelImpl().addRule( rule ).addGlobal( nameG ).addGlobal( resultG );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );

        KieSession ksession = kieBase.newKieSession();

        ksession.setGlobal( nameG.getName(), "Mark" );

        Result result = new Result();
        ksession.setGlobal( resultG.getName(), result );

        Person mark = new Person("Mark", 37);
        Person edson = new Person("Edson", 35);
        Person mario = new Person("Mario", 40);

        FactHandle markFH = ksession.insert(mark);
        FactHandle edsonFH = ksession.insert(edson);
        FactHandle marioFH = ksession.insert(mario);

        ksession.fireAllRules();
        assertEquals("Mark is 37", result.value);
    }
}
