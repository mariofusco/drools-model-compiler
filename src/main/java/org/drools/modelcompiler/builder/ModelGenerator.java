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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
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
                    IndexUtil.ConstraintType decodeConstraintType = IndexUtil.ConstraintType.decode( relationalExprDescr.getOperator() );
                    // to be visited
                    // TODO what if not atomicExprDescr ?
                    Set<String> usedDeclarations = new HashSet<>();
                    Set<String> reactOnProperties = new HashSet<>();
                    ExpressionTuple left = atomicToPart(context, pattern, (AtomicExprDescr) relationalExprDescr.getLeft(), usedDeclarations, reactOnProperties);
                    ExpressionTuple right = atomicToPart(context, pattern, (AtomicExprDescr) relationalExprDescr.getRight(), usedDeclarations, reactOnProperties);
                    String combo = null;
                    switch( relationalExprDescr.getOperator() ) {
                        case "==":
                            combo = new StringBuilder().append(left.expression).append(".equals(").append(right.expression).append(")").toString();
                            break;
                        default:
                            combo = new StringBuilder().append(left.expression).append(" ").append(relationalExprDescr.getOperator()).append(" ").append(right.expression).toString();
                    }
                    
                    StringBuilder newExpression = new StringBuilder()
                            .append("expr( ")
                            .append( Stream.concat(Stream.of(pattern.getDeclaration().getBindingName()), usedDeclarations.stream()).map(x->"var_"+x).collect(Collectors.joining(", ")) )
                            .append(", ")
                            .append("( ")
                            .append( Stream.concat(Stream.of("_this"), usedDeclarations.stream()).collect(Collectors.joining(", ")) )
                            .append( " ) -> " )
                            .append(combo)
                            .append(" )")
                            ;
                    
                    
                    // -- all indexing stuff --
                    Class<?> indexType = Stream.of(left, right).map(ExpressionTuple::getIndexType)
                            .flatMap(x -> optToStream(x))
                            .findFirst().get();
                    newExpression
                        .append("\n  .indexedBy( ")
                        .append(indexType.getCanonicalName()).append(".class, ")
                        .append("ConstraintType.")
                            .append(decodeConstraintType.toString())
                            .append(", ")
                        .append("_this -> ")
                            .append(left.expression)
                            .append(", ")
                        ;
                    if ( constraint.getType() == Constraint.ConstraintType.ALPHA ) { 
                        newExpression.append(right.expression);
                    } else if ( constraint.getType() == Constraint.ConstraintType.BETA ) {
                        if ( usedDeclarations.size() > 1 ) {
                            throw new UnsupportedOperationException("TODO"); // TODO how to know which declaration is impacting for the beta index?
                        }
                        newExpression
                            .append( usedDeclarations.iterator().next() )
                            .append( " -> " )
                            .append(right.expression)
                            .append(" ")
                            ;
                    } else {
                        throw new UnsupportedOperationException("TODO"); // TODO
                    }
                    newExpression
                        .append(" )");
                    // -- END all indexing stuff --
                    
                    
                    // -- all reactOn stuff --
                    if ( !reactOnProperties.isEmpty() ) {
                        String reactOnCsv = Stream.concat( reactOnProperties.stream(),
                                                           Optional.ofNullable( pattern.getListenedProperties() ).map(Collection::stream).orElseGet(Stream::empty) )
                                .map( p -> new StringBuilder("\"").append(p).append("\"").toString() )
                                .collect(Collectors.joining(", "));
                        newExpression
                            .append("\n  .reactOn( ")
                            .append( reactOnCsv )
                            .append(" )");
                    }
                    // -- END all reactOn stuff --
                    
                    
                        
                    System.out.println("Adding newExpression: "+newExpression);
                    context.expressions.add( newExpression.toString() );
                }
            } else {
                throw new UnsupportedOperationException("TODO"); // TODO
            }
        }
    }
    
    /**
     * waiting for java 9 Optional improvement
     */
    static <T> Stream<T> optToStream(Optional<T> opt) {
        if (opt.isPresent())
            return Stream.of(opt.get());
        else
            return Stream.empty();
    }
    
    static class ExpressionTuple {
        private final String expression;
        private final Optional<Class<?>> indexType;
        public ExpressionTuple(String expression, Optional<Class<?>> indexType) {
            super();
            this.expression = expression;
            this.indexType = indexType;
        }
        public String getExpression() {
            return expression;
        }
        public Optional<Class<?>> getIndexType() {
            return indexType;
        }
    }

    private static ExpressionTuple atomicToPart(RuleContext context, Pattern pattern, AtomicExprDescr atomicExprDescr, Set<String> usedDeclarations, Set<String> reactOnProperties) {
        if ( atomicExprDescr.isLiteral() ) {
            return new ExpressionTuple(atomicExprDescr.getExpression(), Optional.empty());
        } else {
            String expression = atomicExprDescr.getExpression();
            String[] parts = expression.split("\\.");
            StringBuilder telescoping = new StringBuilder();
            boolean implicitThis = true;
            Class<?> typeCursor = ( (ClassObjectType) pattern.getObjectType() ).getClassType();
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
                    if ( ( idx == 0 && implicitThis ) || ( idx == 1 && implicitThis == false ) ) {
                        reactOnProperties.add(part);
                    }
                    Method accessor = ClassUtils.getAccessor( typeCursor, part );
                    typeCursor = accessor.getReturnType();
                    telescoping.append( "." + accessor.getName() + "()" );
                }
            }
            return new ExpressionTuple(implicitThis ? "_this" + telescoping.toString() : telescoping.toString(), Optional.of(typeCursor)); 
        }
    }

    public static class RuleContext {
        Map<String, Class<?>> declarations = new HashMap<>();
        List<String> expressions = new ArrayList<>();
    }
}