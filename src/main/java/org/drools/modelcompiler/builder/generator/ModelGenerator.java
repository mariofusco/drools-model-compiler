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
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import org.drools.model.Rule;
import org.drools.model.Variable;
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
            
            MethodDeclaration ruleMethod = new MethodDeclaration();
            ruleMethod.setModifiers(EnumSet.of(Modifier.PRIVATE));
            ClassOrInterfaceType ruleType = JavaParser.parseClassOrInterfaceType(Rule.class.getCanonicalName());
            ruleMethod.setType(ruleType);
            ruleMethod.setName(new StringBuilder("rule_").append(rule.getId()).toString());
            BlockStmt ruleBlock = new BlockStmt();
            ruleMethod.setBody(ruleBlock);
            for ( Entry<String, Class<?>> decl : context.declarations.entrySet() ) {
                ClassOrInterfaceType var_type = JavaParser.parseClassOrInterfaceType(Variable.class.getCanonicalName());
                ClassOrInterfaceType declType = JavaParser.parseClassOrInterfaceType( decl.getValue().getCanonicalName() );
                
                var_type.setTypeArguments(declType);
                VariableDeclarationExpr var_ = new VariableDeclarationExpr(var_type,
                                                                           new StringBuilder("var_").append(decl.getKey()).toString(),
                                                                           Modifier.FINAL);
                
                
                MethodCallExpr variableOfCall = new MethodCallExpr(null, "variableOf");
                MethodCallExpr typeCall = new MethodCallExpr(null, "type");
                typeCall.addArgument( new ClassExpr( declType ));
                variableOfCall.addArgument(typeCall);
                
                AssignExpr var_assign = new AssignExpr(var_, variableOfCall, AssignExpr.Operator.ASSIGN);
                ruleBlock.addStatement(var_assign);
            }
            VariableDeclarationExpr ruleVar = new VariableDeclarationExpr(ruleType, "rule");
            
            MethodCallExpr ruleCall = new MethodCallExpr(null, "rule");
            ruleCall.addArgument( new StringLiteralExpr( rule.getName() ) );
             
            MethodCallExpr viewCall = new MethodCallExpr(ruleCall, "view");
            context.expressions.stream().forEach(viewCall::addArgument);
            
            MethodCallExpr thenCall = new MethodCallExpr(viewCall, "then");
            LambdaExpr thenLambda = new LambdaExpr();
            thenCall.addArgument(thenLambda);
            thenLambda.addParameter(new Parameter(new UnknownType(), "c"));
            NameExpr cNameExpr = new NameExpr("c");
            MethodCallExpr onCall = new MethodCallExpr(cNameExpr, "on");
            context.declarations.entrySet().stream().map(d -> new StringBuilder("var_").append(d.getKey()).toString()).forEach(onCall::addArgument);
            
            MethodCallExpr executeCall = new MethodCallExpr(onCall, "execute");
            LambdaExpr executeLambda = new LambdaExpr();
            executeCall.addArgument(executeLambda);
            executeLambda.setEnclosingParameters(true);
            context.declarations.keySet().stream().map(x -> new Parameter(new UnknownType(), x)).forEach(executeLambda::addParameter);
            String ruleConsequenceAsBlock = new StringBuilder("{").append(r.getDescr().getConsequence().toString().trim()).append("}").toString();
            executeLambda.setBody( JavaParser.parseBlock( ruleConsequenceAsBlock ) );
            
            Expression thenLambdaExpr = executeCall;
            thenLambda.setBody( new ExpressionStmt( thenLambdaExpr ) );
            
            AssignExpr ruleAssign = new AssignExpr(ruleVar, thenCall, AssignExpr.Operator.ASSIGN);
            ruleBlock.addStatement(ruleAssign);
            
            ruleBlock.addStatement( new ReturnStmt("rule") );
            System.out.println(ruleMethod);
            packageModel.putRuleMethod("rule_" + rule.getId(), ruleMethod);
            
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
            Expression dslExpr = drlxParse(context, pattern, constraint, expression);

            System.out.println("Adding newExpression: "+dslExpr);
            context.expressions.add( dslExpr );
        }
    }

    private static Expression drlxParse(RuleContext context, Pattern pattern, Constraint constraint, String expression) {
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

    private static Expression mvelParse(RuleContext context, Pattern pattern, Constraint constraint, String expression) {
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

    private static Expression buildDslExpression( Pattern pattern, Constraint constraint, ConstraintType decodeConstraintType,
                                              Set<String> usedDeclarations, Set<String> reactOnProperties,
                                              IndexedExpression left, IndexedExpression right, Expression combo ) {
        
        MethodCallExpr exprDSL = new MethodCallExpr(null, "expr");
        exprDSL.addArgument( new NameExpr("var_" + pattern.getDeclaration().getBindingName()) );
        usedDeclarations.stream().map( x -> new NameExpr( "var_" + x )).forEach(exprDSL::addArgument);
        
        LambdaExpr exprDSL_predicate = new LambdaExpr();
        exprDSL_predicate.setEnclosingParameters(true);
        exprDSL_predicate.addParameter(new Parameter(new UnknownType(), "_this"));
        usedDeclarations.stream().map( s -> new Parameter(new UnknownType(), s) ).forEach(exprDSL_predicate::addParameter);
        exprDSL_predicate.setBody( new ExpressionStmt( combo ) );
        
        exprDSL.addArgument(exprDSL_predicate);
        
        // -- all indexing stuff --
        Class<?> indexType = Stream.of( left, right ).map( IndexedExpression::getIndexType )
                                   .flatMap( x -> optToStream( x ) )
                                   .findFirst().get();
        
        ClassExpr indexedBy_indexedClass = new ClassExpr( new ClassOrInterfaceType( indexType.getCanonicalName() ) );
        FieldAccessExpr indexedBy_constraintType = new FieldAccessExpr( new NameExpr( "org.drools.model.Index.ConstraintType" ), decodeConstraintType.toString()); // not 100% accurate as the type in "nameExpr" is actually parsed if it was JavaParsers as a big chain of FieldAccessExpr
        LambdaExpr indexedBy_leftOperandExtractor = new LambdaExpr();
        indexedBy_leftOperandExtractor.addParameter(new Parameter(new UnknownType(), "_this"));
        indexedBy_leftOperandExtractor.setBody( new ExpressionStmt( left.getExpression() ) );

        MethodCallExpr indexedByDSL = new MethodCallExpr(exprDSL, "indexedBy");
        indexedByDSL.addArgument( indexedBy_indexedClass );
        indexedByDSL.addArgument( indexedBy_constraintType );
        indexedByDSL.addArgument( indexedBy_leftOperandExtractor );
        if ( constraint.getType() == Constraint.ConstraintType.ALPHA ) {
            Expression indexedBy_rightValue = right.getExpression();
            indexedByDSL.addArgument( indexedBy_rightValue );
        } else if ( constraint.getType() == Constraint.ConstraintType.BETA ) {
            if ( usedDeclarations.size() > 1 ) {
                throw new UnsupportedOperationException( "UNKNOWN" ); // TODO how to know which declaration is impacting for the beta index?
            }
            LambdaExpr indexedBy_rightOperandExtractor = new LambdaExpr();
            indexedBy_rightOperandExtractor.addParameter(new Parameter(new UnknownType(), usedDeclarations.iterator().next()));
            indexedBy_rightOperandExtractor.setBody( new ExpressionStmt( right.getExpression() ) );
            indexedByDSL.addArgument( indexedBy_rightOperandExtractor );
        } else {
            throw new UnsupportedOperationException( "TODO" ); // TODO
        }
        // -- END all indexing stuff --

        // -- all reactOn stuff --
        if ( !reactOnProperties.isEmpty() ) {
            MethodCallExpr reactOnDSL = new MethodCallExpr(indexedByDSL, "reactOn");
            Stream.concat( reactOnProperties.stream(),
                           Optional.ofNullable( pattern.getListenedProperties() ).map( Collection::stream ).orElseGet( Stream::empty ) )
                           .map( p -> new StringLiteralExpr( p ) )
                           .forEach( reactOnDSL::addArgument );
            
            return reactOnDSL;
        }

        return indexedByDSL;
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
        List<Expression> expressions = new ArrayList<>();
    }
}