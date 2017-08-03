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

import org.drools.model.Rule;
import org.drools.model.Source;
import org.drools.model.Variable;
import org.drools.modelcompiler.builder.KieBaseBuilder;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.rule.DataSource;
import org.kie.api.runtime.rule.RuleUnit;
import org.kie.api.runtime.rule.RuleUnitExecutor;

import static java.util.Arrays.asList;
import static org.drools.model.DSL.*;
import static org.junit.Assert.assertTrue;

public class RuleUnitTest {
    public static class AdultUnit implements RuleUnit {
        private DataSource<Person> persons;

        public AdultUnit( ) { }

        public AdultUnit( DataSource<Person> persons ) {
            this.persons = persons;
        }

        public DataSource<Person> getPersons() {
            return persons;
        }
    }

    @Test
    public void testRuleUnit() {
        List<String> result = new ArrayList<String>();

        Variable<Person> adult = variableOf( type( Person.class ) );
        Source<Person> persons = sourceOf( "persons", type( Person.class ) );

        Rule rule = rule( "org.drools.retebuilder", "Adult" ).unit( AdultUnit.class )
                     .view(
                             from( persons ).filter( adult, p -> p.getAge() > 18 )
                          )
                     .then(on(adult).execute(p -> {
                         System.out.println( p.getName() );
                         result.add( p.getName() );
                     }));

        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( () -> asList( rule ) );

        RuleUnitExecutor executor = RuleUnitExecutor.create().bind( kieBase );

        executor.newDataSource( "persons",
                                new Person( "Mario", 43 ),
                                new Person( "Marilena", 44 ),
                                new Person( "Sofia", 5 ) );

        executor.run( AdultUnit.class );

        assertTrue( result.containsAll( asList("Mario", "Marilena") ) );
    }
}