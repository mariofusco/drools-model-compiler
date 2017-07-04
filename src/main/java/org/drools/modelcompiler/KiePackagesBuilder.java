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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.drools.core.RuleBaseConfiguration;
import org.drools.core.base.ClassObjectType;
import org.drools.core.base.extractors.ArrayElementReader;
import org.drools.core.base.extractors.SelfReferenceClassFieldReader;
import org.drools.core.definitions.impl.KnowledgePackageImpl;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.Accumulate;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.MultiAccumulate;
import org.drools.core.rule.Pattern;
import org.drools.core.rule.RuleConditionElement;
import org.drools.core.rule.SingleAccumulate;
import org.drools.core.spi.Accumulator;
import org.drools.core.spi.InternalReadAccessor;
import org.drools.model.AccumulateFunction;
import org.drools.model.AccumulatePattern;
import org.drools.model.Condition;
import org.drools.model.Consequence;
import org.drools.model.Constraint;
import org.drools.model.Model;
import org.drools.model.Rule;
import org.drools.model.SingleConstraint;
import org.drools.model.Variable;
import org.drools.model.View;
import org.drools.modelcompiler.consequence.LambdaConsequence;
import org.drools.modelcompiler.constraints.ConstraintEvaluator;
import org.drools.modelcompiler.constraints.LambdaAccumulator;
import org.drools.modelcompiler.constraints.LambdaConstraint;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.definition.KiePackage;

import static org.drools.core.rule.Pattern.getReadAcessor;
import static org.drools.modelcompiler.ModelCompilerUtil.conditionToGroupElementType;

public class KiePackagesBuilder {

    private final RuleBaseConfiguration configuration;

    private Map<String, KiePackage> packages = new HashMap<>();

    private Set<Class<?>> patternClasses = new HashSet<>();

    public KiePackagesBuilder( KieBaseConfiguration conf ) {
        this.configuration = ( (RuleBaseConfiguration) conf );
    }

    public void addModel( Model model ) {
        for (Rule rule : model.getRules()) {
            KnowledgePackageImpl pkg = (KnowledgePackageImpl) packages.computeIfAbsent( rule.getPackge(), KnowledgePackageImpl::new );
            pkg.addRule( compileRule( rule ) );
        }
    }

    public Collection<Class<?>> getPatternClasses() {
        return patternClasses;
    }

    private RuleImpl compileRule( Rule rule ) {
        RuleImpl ruleImpl = new RuleImpl( rule.getName() );
        ruleImpl.setPackage( rule.getPackge() );
        RuleContext ctx = new RuleContext();
        populateLHS( ctx, ruleImpl.getLhs(), rule.getView() );
        processConsequence( rule, ruleImpl, ctx );
        return ruleImpl;
    }

    private void processConsequence( Rule rule, RuleImpl ruleImpl, RuleContext ctx ) {
        Consequence consequence = rule.getConsequence();
        ruleImpl.setConsequence( new LambdaConsequence( consequence, ctx ) );

        Variable[] consequenceVars = consequence.getDeclarations();
        String[] requiredDeclarations = new String[consequenceVars.length];
        for (int i = 0; i < consequenceVars.length; i++) {
            requiredDeclarations[i] = ctx.getPatternId( consequenceVars[i] );
        }

        ruleImpl.setRequiredDeclarationsForConsequence( RuleImpl.DEFAULT_CONSEQUENCE_NAME, requiredDeclarations );
    }

    private void populateLHS( RuleContext ctx, GroupElement lhs, View view ) {
        view.getSubConditions().forEach( condition -> lhs.addChild( conditionToElement(ctx, condition) ) );
    }

    private RuleConditionElement conditionToElement( RuleContext ctx, Condition condition ) {
        if (condition.getType().isComposite()) {
            GroupElement ge = new GroupElement( conditionToGroupElementType( condition.getType() ) );
            for (Condition subCondition : condition.getSubConditions()) {
                ge.addChild( conditionToElement( ctx, subCondition ) );
            }
            return ge;
        }

        switch (condition.getType()) {
            case PATTERN: {
                return buildPattern( ctx, condition );
            }
            case ACCUMULATE: {
                Pattern source = buildPattern( ctx, condition );
                Pattern pattern = new Pattern( 0, new ClassObjectType( Object.class ) );
                pattern.setSource( buildAccumulate( ctx, (AccumulatePattern) condition, source, pattern ) );
                return pattern;
            }
        }
        throw new UnsupportedOperationException();
    }

    private Pattern buildPattern( RuleContext ctx, Condition condition ) {
        org.drools.model.Pattern modelPattern = (org.drools.model.Pattern) condition;
        Pattern pattern = addPatternForVariable( ctx, modelPattern.getPatternVariable() );
        addConstraintsToPattern( ctx, pattern, modelPattern, modelPattern.getConstraint() );
        return pattern;
    }

    private Accumulate buildAccumulate( RuleContext ctx, AccumulatePattern accPattern, Pattern source, Pattern pattern ) {
        AccumulateFunction<?, ?, ?>[] accFunc = accPattern.getFunctions();

        if (accFunc.length == 1) {
            pattern.addDeclaration( new Declaration(ctx.getPatternId( accPattern.getBoundVariables()[0] ),
                                                    getReadAcessor( new ClassObjectType( Object.class ) ),
                                                    pattern,
                                                    true) );
            return new SingleAccumulate( source, new Declaration[0], new LambdaAccumulator( accPattern.getFunctions()[0]));
        }

        InternalReadAccessor reader = new SelfReferenceClassFieldReader( Object[].class );
        Accumulator[] accumulators = new Accumulator[accFunc.length];
        for (int i = 0; i < accPattern.getFunctions().length; i++) {
            Variable accVar = accPattern.getBoundVariables()[i];
            pattern.addDeclaration( new Declaration(ctx.getPatternId( accVar ),
                                                    new ArrayElementReader( reader, i, accVar.getType().asClass()),
                                                    pattern,
                                                    true) );
            accumulators[i] = new LambdaAccumulator( accFunc[i] );
        }
        return new MultiAccumulate( source, new Declaration[0], accumulators);
    }

    private Pattern addPatternForVariable( RuleContext ctx, Variable patternVariable ) {
        Class<?> patternClass = patternVariable.getType().asClass();
        patternClasses.add( patternClass );
        Pattern pattern = new Pattern( ctx.getNextPatternIndex(),
                                       0, // offset will be set by ReteooBuilder
                                       new ClassObjectType( patternClass ),
                                       ctx.getPatternId( patternVariable ),
                                       true );
        ctx.registerPattern( patternVariable, pattern );
        return pattern;
    }

    private void addConstraintsToPattern( RuleContext ctx, Pattern pattern, org.drools.model.Pattern modelPattern, Constraint constraint ) {
        if (constraint.getType() == Constraint.Type.SINGLE) {
            SingleConstraint singleConstraint = (SingleConstraint) constraint;
            Declaration[] declarations = getRequiredDeclaration(ctx, singleConstraint);
            ConstraintEvaluator constraintEvaluator = new ConstraintEvaluator( declarations, pattern, singleConstraint );
            if (singleConstraint.getVariables().length > 0) {
                pattern.addConstraint( new LambdaConstraint( constraintEvaluator ) );
                addFieldsToPatternWatchlist( pattern, singleConstraint.getReactiveProps() );
            }
        } else if (modelPattern.getConstraint().getType() == Constraint.Type.AND) {
            for (Constraint child : constraint.getChildren()) {
                addConstraintsToPattern(ctx, pattern, modelPattern, child);
            }
        }
    }

    private void addFieldsToPatternWatchlist( Pattern pattern, String[] fields ) {
        if (fields != null && fields.length > 0) {
            Collection<String> watchlist = pattern.getListenedProperties();
            if ( watchlist == null ) {
                watchlist = new HashSet<>( );
                pattern.setListenedProperties( watchlist );
            }
            for (String field : fields) {
                watchlist.add( field );
            }
        }
    }

    private Declaration[] getRequiredDeclaration( RuleContext ctx, SingleConstraint singleConstraint ) {
        return Stream.of( singleConstraint.getVariables() )
                     .map( ctx::getPattern )
                     .map( Pattern::getDeclaration )
                     .toArray( Declaration[]::new );
    }

    public Collection<KiePackage> getKnowledgePackages() {
        return packages.values();
    }
}
