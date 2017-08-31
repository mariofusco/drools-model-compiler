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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.drools.model.Model;
import org.drools.modelcompiler.builder.generator.DRLExprIdGenerator;

public class PackageModel {

    private final String name;
    
    private Set<String> imports = new HashSet<>();

    private Map<String, MethodDeclaration> ruleMethods = new HashMap<>();

    private DRLExprIdGenerator exprIdGenerator;

    public PackageModel( String name ) {
        this.name = name;
        exprIdGenerator = new DRLExprIdGenerator();
    }

    public String getName() {
        return name;
    }
    
    public DRLExprIdGenerator getExprIdGenerator() {
        return exprIdGenerator;
    }

    public void addImports(Collection<String> imports) {
        this.imports.addAll(imports);
    }
    
    public void putRuleMethod(String methodName, MethodDeclaration ruleMethod) {
        this.ruleMethods.put(methodName, ruleMethod);
    }

    public String getVarsSource() {
//        if (true) return getVariableSource();
        return null;
    }

    public String getRulesSource() {
//        if (true) return getRuleModelSource();

        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration( name );

        // fixed part
        cu.addImport(JavaParser.parseImport("import java.util.*;"                          ));
        cu.addImport(JavaParser.parseImport("import org.drools.model.*;"                   ));
        cu.addImport(JavaParser.parseImport("import static org.drools.model.DSL.*;"        ));
        cu.addImport(JavaParser.parseImport("import org.drools.model.Index.ConstraintType;"));
        
        // imports from DRL:
        for ( String i : imports ) {
            if ( i.equals(name+".*") ) {
                break; // skip same-package star import.
            }
            cu.addImport(JavaParser.parseImport("import "+i+";"));
        }
        
        ClassOrInterfaceDeclaration rulesClass = cu.addClass("Rules");
        rulesClass.addImplementedType(Model.class);

        BodyDeclaration<?> getRulesMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<Rule> getRules() {\n" +
                "        return rules;\n" +
                "    }\n"
                );
        rulesClass.addMember(getRulesMethod);
        StringBuilder sb = new StringBuilder("\n");
        sb.append("With the following expression ID:\n");
        sb.append(exprIdGenerator.toString());
        sb.append("\n");
        JavadocComment exprIdComment = new JavadocComment(sb.toString());
        getRulesMethod.setComment(exprIdComment);
        
        BodyDeclaration<?> getGlobalsMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<Global> getGlobals() {\n" +
                "        return Collections.emptyList();\n" +
                "    }\n");
        rulesClass.addMember(getGlobalsMethod);
        
        BodyDeclaration<?> rulesList = JavaParser.parseBodyDeclaration("List<Rule> rules = new ArrayList<>();");
        rulesClass.addMember(rulesList);
        // end of fixed part
        
        // instance initializer block.
        // add to `rules` list the result of invoking each method for rule 
        InitializerDeclaration rulesListInitializer = new InitializerDeclaration();
        rulesClass.addMember(rulesListInitializer);
        BlockStmt rulesListInitializerBody = new BlockStmt();
        rulesListInitializer.setBody(rulesListInitializerBody);
        for ( String methodName : ruleMethods.keySet() ) {
            NameExpr rulesFieldName = new NameExpr( "rules" );
            MethodCallExpr add = new MethodCallExpr(rulesFieldName, "add");
            add.addArgument( new MethodCallExpr(null, methodName) );
            rulesListInitializerBody.addStatement( add );
        }
        
        // each method per Drlx parser result
        ruleMethods.values().forEach( rulesClass::addMember );
        
        return cu.toString();
    }

    public void print() {
        System.out.println("=====");
        System.out.println("PackageModel "+name);
        System.out.println("    imports: "+imports);
        System.out.println(getRulesSource());
        System.out.println("=====");
    }
    
    @SuppressWarnings("unused")
    @Deprecated
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

    @SuppressWarnings("unused")
    @Deprecated
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
               "                                .indexedBy( String.class, ConstraintType.EQUAL, Person::getName, \"Mark\" )\n" +
               "                                .reactOn( \"name\", \"age\" ),\n" +
               "                        expr(olderV, p -> !p.getName().equals(\"Mark\"))\n" +
               "                                .indexedBy( String.class, ConstraintType.NOT_EQUAL, Person::getName, \"Mark\" )\n" +
               "                                .reactOn( \"name\" ),\n" +
               "                        expr(olderV, markV, (p1, p2) -> p1.getAge() > p2.getAge())\n" +
               "                                .indexedBy( int.class, ConstraintType.GREATER_THAN, Person::getAge, Person::getAge )\n" +
               "                                .reactOn( \"age\" )\n" +
               "                     )\n" +
               "                .then(c -> c.on(olderV, markV)\n" +
               "                            .execute( (p1, p2) -> System.out.println( p1.getName() + \" is older than \" + p2.getName() ) ) );\n" +
               "        return rule;\n" +
               "    }\n" +
               "}\n";
    }



}
