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

import java.util.Optional;

import com.github.javaparser.ast.expr.Expression;

public class IndexedExpression {

    private Expression expression;
    private String removeMe;
    private Optional<Class<?>> indexType;
    private Expression prefixExpression;

    public IndexedExpression( ) { }

    public IndexedExpression( Expression expression, Optional<Class<?>> indexType ) {
        this.expression = expression;
        this.indexType = indexType;
        this.removeMe = null;
    }
    
    public IndexedExpression( String expression, Optional<Class<?>> indexType ) {
        this.removeMe = expression;
        this.indexType = indexType;
        this.expression = null;
    }
    
    public Expression getExpression() {
        return expression;
    }

    public IndexedExpression setExpression( Expression expression ) {
        this.expression = expression;
        return this;
    }

    public IndexedExpression setIndexType( Optional<Class<?>> indexType ) {
        this.indexType = indexType;
        return this;
    }

    public Expression getPrefixExpression() {
        return prefixExpression;
    }

    public IndexedExpression setPrefixExpression( Expression prefixExpression ) {
        this.prefixExpression = prefixExpression;
        return this;
    }

    public String getExpressionAsString() {
        return expression != null ? expression.toString() : removeMe ;
    }

    public Optional<Class<?>> getIndexType() {
        return indexType;
    }
}