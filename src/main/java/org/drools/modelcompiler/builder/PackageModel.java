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

package org.drools.modelcompiler.builder;

public class PackageModel {

    private final String name;

    private String varsSource;
    private String rulesSource;

    public PackageModel( String name ) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getVarsSource() {
        return getVariableSource();
    }

    public String getRulesSource() {
        return getRuleModelSource();
    }

    private static String getVariableSource() {
        return "package myrules;\n" +
               "" +
               "import org.drools.model.*;\n" +
               "import static org.drools.model.DSL.*;\n" +
               "import org.drools.modelcompiler.Person;\n" +
               "" +
               "public class Variables {\n" +
               "" +
               "    public static final Variable<Person> markV = variableOf( type( Person.class ) );\n" +
               "    public static final Variable<Person> olderV = variableOf( type( Person.class ) );\n" +
               "}\n";
    }

    private static String getRuleModelSource() {
        return "package myrules;\n" +
               "" +
               "import java.util.*;\n" +
               "import org.drools.model.*;\n" +
               "import static org.drools.model.DSL.*;\n" +
               "import org.drools.modelcompiler.Person;\n" +
               "import org.drools.model.Index.ConstraintType;\n" +
               "" +
               "import static myrules.Variables.*;\n" +
               "" +
               "public class Rules implements Model {\n" +
               "" +
               "    int a;\n" + // workaround for ecj bug!
               "" +
               "    @Override\n" +
               "    public List<Rule> getRules() {\n" +
               "        return Arrays.asList( rule1() );\n" +
               "    }\n" +
               "" +
               "    private Rule rule1() {\n" +
               "        Rule rule = rule( \"beta\" )\n" +
               "                .view(\n" +
               "                        expr(markV, p -> p.getName().equals(\"Mark\"))\n" +
               "                                .indexedBy( ConstraintType.EQUAL, Person::getName, \"Mark\" )\n" +
               "                                .reactOn( \"name\", \"age\" ),\n" +
               "                        expr(olderV, p -> !p.getName().equals(\"Mark\"))\n" +
               "                                .reactOn( \"name\" ),\n" +
               "                        expr(olderV, markV, (p1, p2) -> p1.getAge() > p2.getAge())\n" +
               "                                .reactOn( \"age\" )\n" +
               "                     )\n" +
               "                .then(c -> c.on(olderV, markV)\n" +
               "                            .execute( (p1, p2) -> System.out.println( p1.getName() + \" is older than \" + p2.getName() ) ) );\n" +
               "        return rule;\n" +
               "    }\n" +
               "}\n";
    }
}
