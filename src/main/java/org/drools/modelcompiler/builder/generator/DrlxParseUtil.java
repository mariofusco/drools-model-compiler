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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.visitor.GenericVisitor;
import com.github.javaparser.ast.visitor.VoidVisitor;
import org.drools.core.base.ClassObjectType;
import org.drools.core.rule.Pattern;
import org.drools.core.util.ClassUtils;
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.modelcompiler.builder.generator.ModelGenerator.RuleContext;

public class DrlxParseUtil {

    public static IndexUtil.ConstraintType toConstraintType( Operator operator ) {
        switch ( operator ) {
            case EQUALS:
                return ConstraintType.EQUAL;
            case NOT_EQUALS:
                return ConstraintType.NOT_EQUAL;
            case GREATER:
                return ConstraintType.GREATER_THAN;
            case GREATER_EQUALS:
                return ConstraintType.GREATER_OR_EQUAL;
            case LESS:
                return ConstraintType.LESS_THAN;
            case LESS_EQUALS:
                return ConstraintType.LESS_OR_EQUAL;
        }
        throw new UnsupportedOperationException( "Unknown operator " + operator );
    }

    public static TypedExpression toTypedExpression( RuleContext context, Pattern pattern, Expression drlxExpr,
                                                     Set<String> usedDeclarations, Set<String> reactOnProperties ) {
        
        Class<?> typeCursor = ( (ClassObjectType) pattern.getObjectType() ).getClassType();
        
        if ( drlxExpr instanceof LiteralExpr ) {
            return new TypedExpression( drlxExpr.toString(), Optional.empty());
        } else if ( drlxExpr instanceof NameExpr ) {
            String name = drlxExpr.toString();
            reactOnProperties.add(name);
            Method accessor = ClassUtils.getAccessor( typeCursor, name );
            Class<?> accessorReturnType = accessor.getReturnType();
            StringBuilder simpleNameExpr = new StringBuilder("_this").append(".").append(accessor.getName()).append("()");
            
            // wip
            LambdaExpr l = new LambdaExpr();
            l.setEnclosingParameters(true);
            l.addParameter(new Parameter(new UnknownType(), "_this"));
            NameExpr _this = new NameExpr("_this");
            MethodCallExpr body = new MethodCallExpr(_this, name);
            l.setBody( new ExpressionStmt( body ) );
            System.out.println( new ExpressionStmt( l ) );
            
            return new TypedExpression( simpleNameExpr.toString(), Optional.of( accessorReturnType ));
        } else if ( drlxExpr instanceof FieldAccessExpr ) {
            Node node0 = drlxExpr.getChildNodes().get(0);
            Node firstProperty = drlxExpr.getChildNodes().get(1);
            List<Node> subList = drlxExpr.getChildNodes().subList(1, drlxExpr.getChildNodes().size());
            if ( context.declarations.containsKey(node0.toString()) ) {
                usedDeclarations.add( node0.toString() );
            } else {
                throw new UnsupportedOperationException("referring to a declaration I don't know about");
                // TODO would it be fine to assume is a global if it's not in the declarations?
            }
            reactOnProperties.add( firstProperty.toString() );
            StringBuilder telescoping = new StringBuilder( node0.toString() );
            for ( Node part : subList ) {
                Method accessor = ClassUtils.getAccessor( typeCursor, part.toString() );
                typeCursor = accessor.getReturnType();
                telescoping.append( "." ).append( accessor.getName() ).append( "()" );
            }
            return new TypedExpression( telescoping.toString(), Optional.of( typeCursor ));
        } else {
            // TODO the below should not be needed anymore...
            drlxExpr.getChildNodes();
            String expression = drlxExpr.toString();
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
                    if ( ( idx == 0 && implicitThis ) || ( idx == 1 && implicitThis == false ) ) {
                        reactOnProperties.add(part);
                    }
                    Method accessor = ClassUtils.getAccessor( typeCursor, part );
                    typeCursor = accessor.getReturnType();
                    telescoping.append( "." + accessor.getName() + "()" );
                }
            }
            return new TypedExpression( implicitThis ? "_this" + telescoping.toString() : telescoping.toString(), Optional.of( typeCursor ));
        }
    }
}
