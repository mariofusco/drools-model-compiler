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

import org.drools.core.RuleBaseConfiguration;
import org.drools.core.base.ClassObjectType;
import org.drools.core.definitions.impl.KnowledgePackageImpl;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.Pattern;
import org.drools.core.rule.RuleConditionElement;
import org.drools.model.Condition;
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
import org.kie.internal.definition.KnowledgePackage;

public class KnowledgePackagesBuilder {

    private final RuleBaseConfiguration configuration;

    private Map<String, KnowledgePackage> packages = new HashMap<>();

    private Set<Class<?>> patternClasses = new HashSet<>();

    public KnowledgePackagesBuilder(KieBaseConfiguration conf) {
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
        ruleImpl.setConsequence( new LambdaConsequence( rule.getConsequence(), ctx ) );
        return ruleImpl;
    }

    private void populateLHS( RuleContext ctx, GroupElement lhs, View view ) {
        view.getSubConditions().forEach( condition -> lhs.addChild( conditionToElement(ctx, condition) ) );
    }

    private RuleConditionElement conditionToElement( RuleContext ctx, Condition condition ) {
        switch (condition.getType()) {
            case PATTERN:
                org.drools.model.Pattern modelPattern = (org.drools.model.Pattern) condition;
                Class<?> patternClass = modelPattern.getPatternVariable().getType().asClass();
                patternClasses.add( patternClass );

                Variable patternVariable = modelPattern.getPatternVariable();
                Pattern pattern = new Pattern( ctx.getNextPatternIndex(),
                                               0, // offset is 0 by default
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
            ConstraintEvaluator constraintEvaluator = new ConstraintEvaluator( modelPattern, singleConstraint );
            if (singleConstraint.getVariables().length > 0) {
                pattern.addConstraint( new LambdaConstraint( constraintEvaluator, getRequiredDeclaration(ctx, modelPattern, singleConstraint) ) );
            }
        } else if (modelPattern.getConstraint().getType() == Constraint.Type.AND) {
            for (Constraint child : constraint.getChildren()) {
                addConstraintsToPattern(ctx, pattern, modelPattern, child);
            }
        }
    }

    private Declaration[] getRequiredDeclaration( RuleContext ctx, org.drools.model.Pattern pattern, SingleConstraint singleConstraint ) {
        for (Variable variable : singleConstraint.getVariables()) {
            if (pattern.getPatternVariable() != variable) {
                return new Declaration[] { ctx.getPattern( variable ).getDeclaration() };
            }
        }
        return new Declaration[0];
    }

    public Collection<KnowledgePackage> getKnowledgePackages() {
        return packages.values();
    }
}
