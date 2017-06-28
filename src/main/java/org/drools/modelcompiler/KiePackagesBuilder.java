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
import org.drools.core.definitions.impl.KnowledgePackageImpl;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.Pattern;
import org.drools.core.rule.RuleConditionElement;
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
import org.drools.modelcompiler.constraints.LambdaConstraint;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.definition.KiePackage;

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
            KnowledgePackageImpl pkg = (KnowledgePackageImpl) packages.computeIfAbsent( rule.getPackge(), name -> new KnowledgePackageImpl(name) );
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
        String[] requiredDeclarations = Stream.of( consequence.getDeclarations() )
                                              .map(ctx::getPattern).map( Pattern::getDeclaration )
                                              .map( Declaration::getIdentifier )
                                              .toArray(String[]::new);
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
            case PATTERN:
                org.drools.model.Pattern modelPattern = (org.drools.model.Pattern) condition;
                Class<?> patternClass = modelPattern.getPatternVariable().getType().asClass();
                patternClasses.add( patternClass );

                Variable patternVariable = modelPattern.getPatternVariable();
                Pattern pattern = new Pattern( ctx.getNextPatternIndex(),
                                               0, // offset will be set by ReteooBuilder
                                               new ClassObjectType( patternClass ),
                                               ctx.getPatternId( patternVariable ),
                                               true );
                ctx.registerPattern( patternVariable, pattern );
                addConstraintsToPattern( ctx, pattern, modelPattern, modelPattern.getConstraint() );
                return pattern;
        }
        throw new UnsupportedOperationException();
    }

    private void addConstraintsToPattern( RuleContext ctx, Pattern pattern, org.drools.model.Pattern modelPattern, Constraint constraint ) {
        if (constraint.getType() == Constraint.Type.SINGLE) {
            SingleConstraint singleConstraint = (SingleConstraint) constraint;
            Declaration[] declarations = getRequiredDeclaration(ctx, modelPattern, singleConstraint);
            ConstraintEvaluator constraintEvaluator = new ConstraintEvaluator( declarations, pattern, singleConstraint );
            if (singleConstraint.getVariables().length > 0) {
                pattern.addConstraint( new LambdaConstraint( constraintEvaluator ) );
            }
        } else if (modelPattern.getConstraint().getType() == Constraint.Type.AND) {
            for (Constraint child : constraint.getChildren()) {
                addConstraintsToPattern(ctx, pattern, modelPattern, child);
            }
        }
    }

    private Declaration[] getRequiredDeclaration( RuleContext ctx, org.drools.model.Pattern pattern, SingleConstraint singleConstraint ) {
        return Stream.of( singleConstraint.getVariables() )
                     .map( ctx::getPattern )
                     .map( Pattern::getDeclaration )
                     .toArray( Declaration[]::new );
    }

    public Collection<KiePackage> getKnowledgePackages() {
        return packages.values();
    }
}
