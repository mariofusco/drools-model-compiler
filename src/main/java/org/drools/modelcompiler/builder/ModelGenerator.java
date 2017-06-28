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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.drools.compiler.compiler.DrlExprParser;
import org.drools.compiler.lang.descr.AtomicExprDescr;
import org.drools.compiler.lang.descr.BaseDescr;
import org.drools.compiler.lang.descr.ConstraintConnectiveDescr;
import org.drools.compiler.lang.descr.RelationalExprDescr;
import org.drools.core.base.ClassObjectType;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.Pattern;
import org.drools.core.rule.RuleConditionElement;
import org.drools.core.rule.constraint.MvelConstraint;
import org.drools.core.spi.Constraint;
import org.drools.core.util.ClassUtils;
import org.kie.internal.builder.conf.LanguageLevelOption;

public class ModelGenerator {

    public static PackageModel generateModel( String name, List<RuleDescrImpl> rules ) {
        PackageModel packageModel = new PackageModel( name );
        for ( RuleDescrImpl r : rules ) {
            RuleImpl rule = r.getImpl();
            RuleContext context = new RuleContext();
            GroupElement lhs = ( (RuleImpl) rule ).getLhs();
            visit(context, lhs);
            
            StringBuilder source = new StringBuilder();
            
            source.append("private Rule rule_" + rule.getId() + "() {\n");
            
            context.declarations.entrySet().stream()
                .map(kv -> "  final Variable<"+kv.getValue().getCanonicalName()+"> var_"+kv.getKey()+" = variableOf( type( "+kv.getValue().getCanonicalName()+".class ) );\n")
                .forEach(source::append);
            
            source.append(   "  Rule rule = rule( \"" + rule.getName() + "\" )\n" +
               "  .view(\n\n");
            
            source.append( context.expressions.stream().collect(Collectors.joining(",\n")) );
            
            source.append("\n\n  )\n");
            source.append("  .then(c -> c.on(");
            source.append( context.declarations.keySet().stream().map(x->"var_"+x).collect(Collectors.joining(", ")) );
            source.append(")\n");
            
            source.append("              .execute( (");
            source.append( context.declarations.keySet().stream().collect(Collectors.joining(", ")) );
            source.append(") -> {\n\n");

            source.append( r.getDescr().getConsequence().toString().trim() );
            
            source.append("\n\n})\n");
            
            source.append("  );\n");
            
            source.append("  return rule;\n}\n");
            
            packageModel.putRuleMethod("rule_" + rule.getId(), source.toString());
            
        }
        packageModel.print();
        return packageModel;
    }

    private static void visit( RuleContext context, GroupElement element ) {
        switch (element.getType()) {
            case AND:
                element.getChildren().forEach( elem -> visit(context, elem) );
                break;
            default:
                throw new UnsupportedOperationException("TODO"); // TODO
        }
    }

    private static void visit(RuleContext context, RuleConditionElement conditionElement) {
        if (conditionElement instanceof Pattern) {
            visit( context, (Pattern) conditionElement );
        } else {
            throw new UnsupportedOperationException("TODO"); // TODO
        }
    }

    private static void visit(RuleContext context, Pattern pattern) {
        System.out.println(pattern);
        Class<?> patternType = ( (ClassObjectType) pattern.getObjectType() ).getClassType();
        if (pattern.getDeclaration() != null) {
            context.declarations.put( pattern.getDeclaration().getBindingName(), patternType );
        }
        for (Constraint constraint : pattern.getConstraints()) {
            DrlExprParser drlExprParser = new DrlExprParser( LanguageLevelOption.DRL6_STRICT );
            ConstraintConnectiveDescr result = drlExprParser.parse( ((MvelConstraint)constraint).getExpression() );
            if ( result.getDescrs().size() == 1 ) {
                BaseDescr singletonDescr = result.getDescrs().get(0);
                System.out.println(singletonDescr);
                if ( singletonDescr instanceof RelationalExprDescr ) {
                    RelationalExprDescr relationalExprDescr = (RelationalExprDescr) singletonDescr;
                    // to be visited
                    // TODO what if not atomicExprDescr ?
                    Set<String> usedDeclarations = new HashSet<>();
                    String left = atomicToPart(context, pattern, (AtomicExprDescr) relationalExprDescr.getLeft(), usedDeclarations);
                    String right = atomicToPart(context, pattern, (AtomicExprDescr) relationalExprDescr.getRight(), usedDeclarations);
                    String combo = null;
                    switch( relationalExprDescr.getOperator() ) {
                        case "==":
                            combo = new StringBuilder().append(left).append(".equals(").append(right).append(")").toString();
                            break;
                        default:
                            combo = new StringBuilder().append(left).append(" ").append(relationalExprDescr.getOperator()).append(" ").append(right).toString();
                    }
                    
                    String newExpression = new StringBuilder()
                            .append("expr( ")
                            .append( Stream.concat(Stream.of(pattern.getDeclaration().getBindingName()), usedDeclarations.stream()).map(x->"var_"+x).collect(Collectors.joining(", ")) )
                            .append(", ")
                            .append("( ")
                            .append( Stream.concat(Stream.of("_this"), usedDeclarations.stream()).collect(Collectors.joining(", ")) )
                            .append( " ) -> " )
                            .append(combo)
                            .append(" )")
                            .toString();
                    System.out.println("Adding newExpression: "+newExpression);
                    context.expressions.add( newExpression );
                }
            } else {
                throw new UnsupportedOperationException("TODO"); // TODO
            }
        }
    }

    private static String atomicToPart(RuleContext context, Pattern pattern, AtomicExprDescr atomicExprDescr, Set<String> usedDeclarations) {
        if ( atomicExprDescr.isLiteral() ) {
            return atomicExprDescr.getExpression();
        } else {
            String expression = atomicExprDescr.getExpression();
            String[] parts = expression.split("\\.");
            StringBuilder telescoping = new StringBuilder();
            boolean implicitThis = true;
            for ( int idx = 0; idx < parts.length ; idx++ ) {
                String part = parts[idx];
                boolean isGlobal = false;
                if ( isGlobal ) {
                    implicitThis = false;
                    telescoping.append( part );
                } else if ( idx == 0 && context.declarations.containsKey(part) ) {
                    implicitThis = false;
                    usedDeclarations.add( part );
                    telescoping.append( part );
                } else {
                    Method accessor = ClassUtils.getAccessor(( (ClassObjectType) pattern.getObjectType() ).getClassType(), part);
                    telescoping.append( "." + accessor.getName() + "()" );
                }
            }
            return implicitThis ? "_this" + telescoping.toString() : telescoping.toString(); 
        }
    }

    public static class RuleContext {
        Map<String, Class<?>> declarations = new HashMap<>();
        List<String> expressions = new ArrayList<>();
    }
}