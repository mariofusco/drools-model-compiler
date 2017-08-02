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

package org.drools.modelcompiler.builder.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.UnknownType;
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
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.drlx.DrlxParser;
import org.drools.modelcompiler.builder.PackageModel;
import org.drools.modelcompiler.builder.RuleDescrImpl;
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
            String expression = ((MvelConstraint)constraint).getExpression();
//            String dslExpr = mvelParse(context, pattern, constraint, expression);
            String dslExpr = drlxParse(context, pattern, constraint, expression);

            System.out.println("Adding newExpression: "+dslExpr);
            context.expressions.add( dslExpr );
        }
    }

    private static String drlxParse(RuleContext context, Pattern pattern, Constraint constraint, String expression) {
        Expression drlxExpr = DrlxParser.parseExpression( expression );

        if ( !(drlxExpr instanceof BinaryExpr) ) {
            throw new UnsupportedOperationException("TODO"); // TODO
        }

        BinaryExpr binaryExpr = (BinaryExpr) drlxExpr;
        Operator operator = binaryExpr.getOperator();

        IndexUtil.ConstraintType decodeConstraintType = DrlxParseUtil.toConstraintType( operator );
        Set<String> usedDeclarations = new HashSet<>();
        Set<String> reactOnProperties = new HashSet<>();
        IndexedExpression left = DrlxParseUtil.toTypedExpression( context, pattern, binaryExpr.getLeft(), usedDeclarations, reactOnProperties );
        IndexedExpression right = DrlxParseUtil.toTypedExpression( context, pattern, binaryExpr.getRight(), usedDeclarations, reactOnProperties );

        Expression combo = null;
        switch ( operator ) {
            case EQUALS:
                MethodCallExpr methodCallExpr = new MethodCallExpr( left.getExpression(), "equals" );
                methodCallExpr.addArgument( right.getExpression() ); // don't create NodeList with static method because missing "parent for child" would null and NPE
                combo = methodCallExpr; 
                break;
            case NOT_EQUALS:
                MethodCallExpr methodCallExpr2 = new MethodCallExpr( left.getExpression(), "equals" );
                methodCallExpr2.addArgument( right.getExpression() );
                combo = methodCallExpr2; 
                combo = new UnaryExpr( combo, UnaryExpr.Operator.LOGICAL_COMPLEMENT );
                break;
            default:
                combo = new BinaryExpr( left.getExpression(), right.getExpression(), operator );
        }

        return buildDslExpression( pattern, constraint, decodeConstraintType, usedDeclarations, reactOnProperties, left, right, combo );
    }

    private static String mvelParse(RuleContext context, Pattern pattern, Constraint constraint, String expression) {
        DrlExprParser drlExprParser = new DrlExprParser( LanguageLevelOption.DRL6_STRICT );
        ConstraintConnectiveDescr result = drlExprParser.parse( expression );
        if ( result.getDescrs().size() != 1 ) {
            throw new UnsupportedOperationException("TODO"); // TODO
        }

        BaseDescr singletonDescr = result.getDescrs().get(0);
        if ( !(singletonDescr instanceof RelationalExprDescr) ) {
            throw new UnsupportedOperationException("TODO"); // TODO
        }

        System.out.println(singletonDescr);
        RelationalExprDescr relationalExprDescr = (RelationalExprDescr) singletonDescr;
        IndexUtil.ConstraintType decodeConstraintType = IndexUtil.ConstraintType.decode( relationalExprDescr.getOperator() );
        // to be visited
        // TODO what if not atomicExprDescr ?
        Set<String> usedDeclarations = new HashSet<>();
        Set<String> reactOnProperties = new HashSet<>();
        IndexedExpression left = MvelParseUtil.toTypedExpression( context, pattern, (AtomicExprDescr) relationalExprDescr.getLeft(), usedDeclarations, reactOnProperties );
        IndexedExpression right = MvelParseUtil.toTypedExpression( context, pattern, (AtomicExprDescr) relationalExprDescr.getRight(), usedDeclarations, reactOnProperties );
        String combo = null;
        switch ( relationalExprDescr.getOperator() ) {
            case "==":
                combo = new StringBuilder().append( left.getExpressionAsString() ).append( ".equals(" ).append( right.getExpressionAsString() ).append( ")" ).toString();
                break;
            case "!=":
                combo = new StringBuilder().append( "!" ).append( left.getExpressionAsString() ).append( ".equals(" ).append( right.getExpressionAsString() ).append( ")" ).toString();
                break;
            default:
                combo = new StringBuilder().append( left.getExpressionAsString() ).append( " " ).append( relationalExprDescr.getOperator() ).append( " " ).append( right.getExpressionAsString() ).toString();
        }

        return buildDslExpression( pattern, constraint, decodeConstraintType, usedDeclarations, reactOnProperties, left, right, new NameExpr( combo ) );
    }

    private static String buildDslExpression( Pattern pattern, Constraint constraint, ConstraintType decodeConstraintType,
                                              Set<String> usedDeclarations, Set<String> reactOnProperties,
                                              IndexedExpression left, IndexedExpression right, Expression combo ) {
        
        LambdaExpr exprPredicate = new LambdaExpr();
        exprPredicate.setEnclosingParameters(true);
        Stream.concat( Stream.of( "_this" ), usedDeclarations.stream() ).map( s -> new Parameter(new UnknownType(), s) ).forEach(exprPredicate::addParameter);
        exprPredicate.setBody( new ExpressionStmt( combo ) );
        
        StringBuilder newExpression = new StringBuilder()
                .append( "expr( " )
                .append( Stream.concat( Stream.of( pattern.getDeclaration().getBindingName() ), usedDeclarations.stream() ).map( x -> "var_" + x ).collect( Collectors.joining( ", " ) ) )
                .append( ", " )
                .append( exprPredicate )
                .append( " )" );


        // -- all indexing stuff --
        Class<?> indexType = Stream.of( left, right ).map( IndexedExpression::getIndexType )
                                   .flatMap( x -> optToStream( x ) )
                                   .findFirst().get();
        newExpression
                .append( "\n  .indexedBy( " )
                .append( indexType.getCanonicalName() ).append( ".class, " )
                .append( "ConstraintType." )
                .append( decodeConstraintType.toString() )
                .append( ", " )
                .append( "_this -> " )
                .append( left.getExpressionAsString() )
                .append( ", " )
        ;
        if ( constraint.getType() == Constraint.ConstraintType.ALPHA ) {
            newExpression.append( right.getExpressionAsString() );
        } else if ( constraint.getType() == Constraint.ConstraintType.BETA ) {
            if ( usedDeclarations.size() > 1 ) {
                throw new UnsupportedOperationException( "TODO" ); // TODO how to know which declaration is impacting for the beta index?
            }
            newExpression
                    .append( usedDeclarations.iterator().next() )
                    .append( " -> " )
                    .append( right.getExpressionAsString() )
                    .append( " " )
            ;
        } else {
            throw new UnsupportedOperationException( "TODO" ); // TODO
        }
        newExpression.append( " )" );
        // -- END all indexing stuff --


        // -- all reactOn stuff --
        if ( !reactOnProperties.isEmpty() ) {
            String reactOnCsv = Stream.concat( reactOnProperties.stream(),
                                               Optional.ofNullable( pattern.getListenedProperties() ).map( Collection::stream ).orElseGet( Stream::empty ) )
                                      .map( p -> new StringBuilder( "\"" ).append( p ).append( "\"" ).toString() )
                                      .collect( Collectors.joining( ", " ) );
            newExpression
                    .append( "\n  .reactOn( " )
                    .append( reactOnCsv )
                    .append( " )" );
        }

        return newExpression.toString();
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

    public static class RuleContext {
        Map<String, Class<?>> declarations = new HashMap<>();
        List<String> expressions = new ArrayList<>();
    }
}