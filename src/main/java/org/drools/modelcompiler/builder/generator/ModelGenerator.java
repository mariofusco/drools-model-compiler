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
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
import org.drools.compiler.lang.descr.AndDescr;
import org.drools.compiler.lang.descr.AtomicExprDescr;
import org.drools.compiler.lang.descr.BaseDescr;
import org.drools.compiler.lang.descr.ConstraintConnectiveDescr;
import org.drools.compiler.lang.descr.OrDescr;
import org.drools.compiler.lang.descr.PatternDescr;
import org.drools.compiler.lang.descr.RelationalExprDescr;
import org.drools.compiler.lang.descr.RuleDescr;
import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.core.rule.Pattern;
import org.drools.core.util.ClassUtils;
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.drlx.DrlxParser;
import org.drools.model.BitMask;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.modelcompiler.builder.PackageModel;
import org.drools.modelcompiler.builder.RuleDescrImpl;
import org.kie.internal.builder.conf.LanguageLevelOption;

import static org.drools.modelcompiler.builder.generator.StringUtil.toId;

public class ModelGenerator {

    private static final ClassOrInterfaceType RULE_TYPE = JavaParser.parseClassOrInterfaceType( Rule.class.getCanonicalName() );
    private static final ClassOrInterfaceType BITMASK_TYPE = JavaParser.parseClassOrInterfaceType( BitMask.class.getCanonicalName() );

    public static PackageModel generateModel( InternalKnowledgePackage pkg, List<RuleDescrImpl> rules ) {
        String name = pkg.getName();
        PackageModel packageModel = new PackageModel( name );
        packageModel.addImports(pkg.getTypeResolver().getImports());
        for ( RuleDescrImpl descr : rules ) {
            RuleDescr ruleDescr = descr.getDescr();
            RuleContext context = new RuleContext( pkg );
//            visit(context, descr.getImpl().getLhs());
            visit(context, ruleDescr.getLhs());

            MethodDeclaration ruleMethod = new MethodDeclaration();
            ruleMethod.setModifiers(EnumSet.of(Modifier.PRIVATE));
            ruleMethod.setType( RULE_TYPE );
            ruleMethod.setName( "rule_" + toId( ruleDescr.getName() ) );
            BlockStmt ruleBlock = new BlockStmt();
            ruleMethod.setBody(ruleBlock);

            for ( Entry<String, Class<?>> decl : context.declarations.entrySet() ) {
                ClassOrInterfaceType varType = JavaParser.parseClassOrInterfaceType(Variable.class.getCanonicalName());
                ClassOrInterfaceType declType = JavaParser.parseClassOrInterfaceType( decl.getValue().getCanonicalName() );
                
                varType.setTypeArguments(declType);
                VariableDeclarationExpr var_ = new VariableDeclarationExpr(varType, "var_" + decl.getKey(), Modifier.FINAL);
                
                MethodCallExpr variableOfCall = new MethodCallExpr(null, "variableOf");
                MethodCallExpr typeCall = new MethodCallExpr(null, "type");
                typeCall.addArgument( new ClassExpr( declType ));
                variableOfCall.addArgument(typeCall);
                
                AssignExpr var_assign = new AssignExpr(var_, variableOfCall, AssignExpr.Operator.ASSIGN);
                ruleBlock.addStatement(var_assign);
            }

            VariableDeclarationExpr ruleVar = new VariableDeclarationExpr( RULE_TYPE, "rule");
            
            MethodCallExpr ruleCall = new MethodCallExpr(null, "rule");
            ruleCall.addArgument( new StringLiteralExpr( ruleDescr.getName() ) );
             
            MethodCallExpr viewCall = new MethodCallExpr(ruleCall, "view");
            viewCall.addArgument(context.expression);
            
            String ruleConsequenceAsBlock = "{" + ruleDescr.getConsequence().toString().trim() + "}";
            BlockStmt ruleConsequence = JavaParser.parseBlock( ruleConsequenceAsBlock );
            List<String> declUsedInRHS = ruleConsequence.getChildNodesByType(NameExpr.class).stream().map(NameExpr::getNameAsString).collect(Collectors.toList());
            List<String> verifiedDeclUsedInRHS = context.declarations.keySet().stream().filter(declUsedInRHS::contains).collect(Collectors.toList());
            
            boolean rhsRewritten = rewriteRHS(context, ruleBlock, ruleConsequence);
            
            MethodCallExpr thenCall = new MethodCallExpr(viewCall, "then");
            MethodCallExpr onCall = new MethodCallExpr(null, "on");
            verifiedDeclUsedInRHS.stream().map(k -> "var_" + k ).forEach( onCall::addArgument );
            
            MethodCallExpr executeCall = new MethodCallExpr(onCall, "execute");
            LambdaExpr executeLambda = new LambdaExpr();
            executeCall.addArgument(executeLambda);
            executeLambda.setEnclosingParameters(true);
            if (rhsRewritten) {
                executeLambda.addParameter(new Parameter(new UnknownType(), "drools"));
            }
            verifiedDeclUsedInRHS.stream().map(x -> new Parameter(new UnknownType(), x)).forEach(executeLambda::addParameter);    
            executeLambda.setBody( ruleConsequence );

            thenCall.addArgument( executeCall );
            
            AssignExpr ruleAssign = new AssignExpr(ruleVar, thenCall, AssignExpr.Operator.ASSIGN);
            ruleBlock.addStatement(ruleAssign);
            
            ruleBlock.addStatement( new ReturnStmt("rule") );
            System.out.println(ruleMethod);
            packageModel.putRuleMethod("rule_" + toId( ruleDescr.getName() ), ruleMethod);
        }

        packageModel.print();
        return packageModel;
    }

    private static boolean rewriteRHS(RuleContext context, BlockStmt ruleBlock, BlockStmt rhs) {
        List<MethodCallExpr> methodCallExprs = rhs.getChildNodesByType(MethodCallExpr.class);
        List<MethodCallExpr> updateExprs = new ArrayList<>();

        boolean hasWMAs = methodCallExprs.stream()
           .filter(mce -> isWMAMethod( mce ) )
           .peek( mce -> {
                if (!mce.getScope().isPresent()) {
                    mce.setScope(new NameExpr("drools"));
                }
                if (mce.getNameAsString().equals("update")) {
                    updateExprs.add( mce );
                }
           })
           .count() > 0;

        for (MethodCallExpr updateExpr : updateExprs) {
            Expression argExpr = updateExpr.getArgument( 0 );
            if (argExpr instanceof NameExpr) {
                String updatedVar = ( (NameExpr) argExpr ).getNameAsString();
                Class<?> updatedClass = context.declarations.get( updatedVar );

                MethodCallExpr bitMaskCreation = new MethodCallExpr( new NameExpr( BitMask.class.getCanonicalName() ), "getPatternMask" );
                bitMaskCreation.addArgument( new ClassExpr( JavaParser.parseClassOrInterfaceType( updatedClass.getCanonicalName() ) ) );

                methodCallExprs.subList( 0, methodCallExprs.indexOf( updateExpr ) ).stream()
                               .filter( mce -> mce.getScope().isPresent() && hasScope( mce, updatedVar ) )
                               .map( mce -> ClassUtils.setter2property( mce.getNameAsString() ) )
                               .filter( o -> o != null )
                               .distinct()
                               .forEach( s -> bitMaskCreation.addArgument( new StringLiteralExpr( s ) ) );

                VariableDeclarationExpr bitMaskVar = new VariableDeclarationExpr(BITMASK_TYPE, "mask_" + updatedVar, Modifier.FINAL);
                AssignExpr bitMaskAssign = new AssignExpr(bitMaskVar, bitMaskCreation, AssignExpr.Operator.ASSIGN);
                ruleBlock.addStatement(bitMaskAssign);

                updateExpr.addArgument( "mask_" + updatedVar );
            }
        }

        return hasWMAs;
    }

    private static boolean isWMAMethod( MethodCallExpr mce ) {
        return isDroolsScopeInWMA( mce ) && (
                mce.getNameAsString().equals("insert") ||
                mce.getNameAsString().equals("delete") ||
                mce.getNameAsString().equals("update") );
    }

    private static boolean isDroolsScopeInWMA( MethodCallExpr mce ) {
        return !mce.getScope().isPresent() || hasScope( mce, "drools" );
    }

    private static boolean hasScope( MethodCallExpr mce, String scope ) {
        return mce.getScope().get() instanceof NameExpr &&
               ( (NameExpr) mce.getScope().get() ).getNameAsString().equals( scope );
    }

    private static void visit( RuleContext context, BaseDescr descr ) {
        if ( descr instanceof AndDescr ) {
            visit( context, ( (AndDescr) descr ));
        } else if ( descr instanceof OrDescr ) {
            visit( context, ( (OrDescr) descr ));
        } else if ( descr instanceof PatternDescr ) {
            visit( context, ( (PatternDescr) descr ));
        } else {
            throw new UnsupportedOperationException("TODO"); // TODO
        }
    }

    private static void visit( RuleContext context, AndDescr descr ) {
        final MethodCallExpr andDSL = new MethodCallExpr(null, "and");
        context.addExpression(andDSL);
        context.pushExprPointer( e -> andDSL.addArgument( e ));
        for (BaseDescr subDescr : descr.getDescrs()) {
            visit( context, subDescr );
        }
        context.popExprPointer();
    }
    
    private static void visit( RuleContext context, OrDescr descr ) {
        final MethodCallExpr orDSL = new MethodCallExpr(null, "or");
        context.addExpression(orDSL);
        context.pushExprPointer( e -> orDSL.addArgument( e ));
        for (BaseDescr subDescr : descr.getDescrs()) {
            visit( context, subDescr );
        }
        context.popExprPointer();
    }

    private static void visit(RuleContext context, PatternDescr pattern ) {
        Class<?> patternType;
        try {
            patternType = context.pkg.getTypeResolver().resolveType( pattern.getObjectType() );
        } catch (ClassNotFoundException e) {
            throw new RuntimeException( e );
        }

        if (pattern.getIdentifier() != null) {
            context.declarations.put( pattern.getIdentifier(), patternType );
        }

        for (BaseDescr constraint : pattern.getConstraint().getDescrs()) {
            String expression = constraint.toString();
            Expression dslExpr = drlxParse(context, patternType, pattern.getIdentifier(), expression);

            System.out.println("Adding newExpression: "+dslExpr);
            context.addExpression( dslExpr );
        }
    }

    private static Expression drlxParse(RuleContext context, Class<?> patternType, String bindingId, String expression) {
        Expression drlxExpr = DrlxParser.parseExpression( expression );

        if ( !(drlxExpr instanceof BinaryExpr) ) {
            throw new UnsupportedOperationException("TODO"); // TODO
        }

        BinaryExpr binaryExpr = (BinaryExpr) drlxExpr;
        Operator operator = binaryExpr.getOperator();

        IndexUtil.ConstraintType decodeConstraintType = DrlxParseUtil.toConstraintType( operator );
        Set<String> usedDeclarations = new HashSet<>();
        Set<String> reactOnProperties = new HashSet<>();
        TypedExpression left = DrlxParseUtil.toTypedExpression( context, patternType, binaryExpr.getLeft(), usedDeclarations, reactOnProperties );
        TypedExpression right = DrlxParseUtil.toTypedExpression( context, patternType, binaryExpr.getRight(), usedDeclarations, reactOnProperties );

        Expression combo;
        if ( left.isPrimitive() ) {
            combo = new BinaryExpr( left.getExpression(), right.getExpression(), operator );
        } else {
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
        }

        if (left.getPrefixExpression() != null) {
            combo = new BinaryExpr( left.getPrefixExpression(), combo, Operator.AND );
        }

        return buildDslExpression( Collections.emptyList(), bindingId, decodeConstraintType, usedDeclarations, reactOnProperties, left, right, combo );
    }

    private static Expression mvelParse(RuleContext context, Pattern pattern, String bindingId, String expression) {
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
        TypedExpression left = MvelParseUtil.toTypedExpression( context, pattern, (AtomicExprDescr) relationalExprDescr.getLeft(), usedDeclarations, reactOnProperties );
        TypedExpression right = MvelParseUtil.toTypedExpression( context, pattern, (AtomicExprDescr) relationalExprDescr.getRight(), usedDeclarations, reactOnProperties );
        String combo;
        switch ( relationalExprDescr.getOperator() ) {
            case "==":
                combo = left.getExpressionAsString() + ".equals(" + right.getExpressionAsString() + ")";
                break;
            case "!=":
                combo = "!" + left.getExpressionAsString() + ".equals(" + right.getExpressionAsString() + ")";
                break;
            default:
                combo = left.getExpressionAsString() + " " + relationalExprDescr.getOperator() + " " + right.getExpressionAsString();
        }

        return buildDslExpression( Collections.emptyList(), bindingId, decodeConstraintType, usedDeclarations, reactOnProperties, left, right, new NameExpr( combo ) );
    }

    private static Expression buildDslExpression( Collection<String> listenedProperties, String bindingId, ConstraintType decodeConstraintType,
                                                  Set<String> usedDeclarations, Set<String> reactOnProperties,
                                                  TypedExpression left, TypedExpression right, Expression combo ) {
        
        MethodCallExpr exprDSL = new MethodCallExpr(null, "expr");
        exprDSL.addArgument( new NameExpr("var_" + bindingId) );
        usedDeclarations.stream().map( x -> new NameExpr( "var_" + x )).forEach(exprDSL::addArgument);
        
        LambdaExpr exprDSL_predicate = new LambdaExpr();
        exprDSL_predicate.setEnclosingParameters(true);
        exprDSL_predicate.addParameter(new Parameter(new UnknownType(), "_this"));
        usedDeclarations.stream().map( s -> new Parameter(new UnknownType(), s) ).forEach(exprDSL_predicate::addParameter);
        exprDSL_predicate.setBody( new ExpressionStmt( combo ) );
        
        exprDSL.addArgument(exprDSL_predicate);
        
        Expression result = exprDSL;
        
        
        // -- all indexing stuff --
        // .indexBy(..) is only added if left is not an identity expression:
        if ( !(left.getExpression() instanceof NameExpr && ((NameExpr)left.getExpression()).getName().getIdentifier().equals("_this")) ) {
            Class<?> indexType = Stream.of( left, right ).map( TypedExpression::getType )
                                       .flatMap( x -> optToStream( x ) )
                                       .findFirst().get();
            
            ClassExpr indexedBy_indexedClass = new ClassExpr( JavaParser.parseType( indexType.getCanonicalName() ) );
            FieldAccessExpr indexedBy_constraintType = new FieldAccessExpr( new NameExpr( "org.drools.model.Index.ConstraintType" ), decodeConstraintType.toString()); // not 100% accurate as the type in "nameExpr" is actually parsed if it was JavaParsers as a big chain of FieldAccessExpr
            LambdaExpr indexedBy_leftOperandExtractor = new LambdaExpr();
            indexedBy_leftOperandExtractor.addParameter(new Parameter(new UnknownType(), "_this"));
            indexedBy_leftOperandExtractor.setBody( new ExpressionStmt( left.getExpression() ) );
    
            MethodCallExpr indexedByDSL = new MethodCallExpr(exprDSL, "indexedBy");
            indexedByDSL.addArgument( indexedBy_indexedClass );
            indexedByDSL.addArgument( indexedBy_constraintType );
            indexedByDSL.addArgument( indexedBy_leftOperandExtractor );
            if ( usedDeclarations.isEmpty() ) {
                Expression indexedBy_rightValue = right.getExpression();
                indexedByDSL.addArgument( indexedBy_rightValue );
            } else if ( usedDeclarations.size() == 1 ) {
                LambdaExpr indexedBy_rightOperandExtractor = new LambdaExpr();
                indexedBy_rightOperandExtractor.addParameter(new Parameter(new UnknownType(), usedDeclarations.iterator().next()));
                indexedBy_rightOperandExtractor.setBody( new ExpressionStmt( right.getExpression() ) );
                indexedByDSL.addArgument( indexedBy_rightOperandExtractor );
            } else {
                throw new UnsupportedOperationException( "TODO" ); // TODO: possibly not to be indexed
            }
            result = indexedByDSL;
        }
        // -- END all indexing stuff --

        
        // -- all reactOn stuff --
        if ( !reactOnProperties.isEmpty() ) {
            MethodCallExpr reactOnDSL = new MethodCallExpr(result, "reactOn");
            Stream.concat( reactOnProperties.stream(),
                           Optional.ofNullable( listenedProperties ).map( Collection::stream ).orElseGet( Stream::empty ) )
                           .map( StringLiteralExpr::new )
                           .forEach( reactOnDSL::addArgument );

            result = reactOnDSL;
        }

        return result;
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
        private final InternalKnowledgePackage pkg;

        public RuleContext( InternalKnowledgePackage pkg ) {
            this.pkg = pkg;
        }

        Map<String, Class<?>> declarations = new HashMap<>();
        
        Expression expression = null;
        Deque<Consumer<Expression>> exprPointer = new LinkedList<>();
        {
            exprPointer.push( init -> this.expression = init );
        }
        
        public void addExpression(Expression e) {
            exprPointer.peek().accept(e);
        }
        public void pushExprPointer(Consumer<Expression> p) {
            exprPointer.push(p);
        }
        public Consumer<Expression> popExprPointer() {
            return exprPointer.pop();
        }

        public InternalKnowledgePackage getPkg() {
            return pkg;
        }
    }
}