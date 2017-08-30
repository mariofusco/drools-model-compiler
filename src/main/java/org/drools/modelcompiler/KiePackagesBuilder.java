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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.drools.core.RuleBaseConfiguration;
import org.drools.core.base.ClassFieldAccessorCache;
import org.drools.core.base.ClassObjectType;
import org.drools.core.base.ClassTypeResolver;
import org.drools.core.base.TypeResolver;
import org.drools.core.base.extractors.ArrayElementReader;
import org.drools.core.base.extractors.SelfReferenceClassFieldReader;
import org.drools.core.definitions.impl.KnowledgePackageImpl;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.rule.Accumulate;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.EntryPointId;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.MultiAccumulate;
import org.drools.core.rule.Pattern;
import org.drools.core.rule.RuleConditionElement;
import org.drools.core.rule.SingleAccumulate;
import org.drools.core.ruleunit.RuleUnitUtil;
import org.drools.core.spi.Accumulator;
import org.drools.core.spi.GlobalExtractor;
import org.drools.core.spi.InternalReadAccessor;
import org.drools.model.AccumulateFunction;
import org.drools.model.AccumulatePattern;
import org.drools.model.Condition;
import org.drools.model.Consequence;
import org.drools.model.Constraint;
import org.drools.model.Global;
import org.drools.model.Model;
import org.drools.model.OOPath;
import org.drools.model.Rule;
import org.drools.model.SingleConstraint;
import org.drools.model.Variable;
import org.drools.model.View;
import org.drools.modelcompiler.consequence.LambdaConsequence;
import org.drools.modelcompiler.constraints.ConstraintEvaluator;
import org.drools.modelcompiler.constraints.LambdaAccumulator;
import org.drools.modelcompiler.constraints.LambdaConstraint;
import org.drools.modelcompiler.constraints.TemporalConstraintEvaluator;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.definition.KiePackage;
import org.kie.api.definition.type.Role;
import org.kie.api.definition.type.Role.Type;

import static org.drools.core.rule.Pattern.getReadAcessor;
import static org.drools.model.DSL.type;
import static org.drools.model.DSL.variableOf;
import static org.drools.modelcompiler.ModelCompilerUtil.conditionToGroupElementType;

public class KiePackagesBuilder {

    private final RuleBaseConfiguration configuration;

    private Map<String, KiePackage> packages = new HashMap<>();

    private Set<Class<?>> patternClasses = new HashSet<>();

    public KiePackagesBuilder( KieBaseConfiguration conf ) {
        this.configuration = ( (RuleBaseConfiguration) conf );
    }

    public void addModel( Model model ) {
        for (Global global : model.getGlobals()) {
            KnowledgePackageImpl pkg = (KnowledgePackageImpl) packages.computeIfAbsent( global.getPackage(), this::createKiePackage );
            pkg.addGlobal( global.getName(), global.getType().asClass() );
        }
        for (Rule rule : model.getRules()) {
            KnowledgePackageImpl pkg = (KnowledgePackageImpl) packages.computeIfAbsent( rule.getPackage(), this::createKiePackage );
            pkg.addRule( compileRule( pkg, rule ) );
        }
    }

    private KnowledgePackageImpl createKiePackage(String name) {
        KnowledgePackageImpl kpkg = new KnowledgePackageImpl( name );
        kpkg.setClassFieldAccessorCache(new ClassFieldAccessorCache( configuration.getClassLoader() ) );
        TypeResolver typeResolver = new ClassTypeResolver( new HashSet<>( kpkg.getImports().keySet() ),
                                                           configuration.getClassLoader(),
                                                           name );
        typeResolver.addImport( name + ".*" );
        kpkg.setTypeResolver(typeResolver);
        return kpkg;
    }

    public Collection<Class<?>> getPatternClasses() {
        return patternClasses;
    }

    private RuleImpl compileRule( KnowledgePackageImpl pkg, Rule rule ) {
        RuleImpl ruleImpl = new RuleImpl( rule.getName() );
        ruleImpl.setPackage( rule.getPackage() );
        if (rule.getUnit() != null) {
            ruleImpl.setRuleUnitClassName( rule.getUnit() );
            pkg.getRuleUnitRegistry().getRuleUnitFor( ruleImpl );
        }
        RuleContext ctx = new RuleContext( ruleImpl );
        populateLHS( ctx, pkg, rule.getView() );
        processConsequence( ctx, rule.getConsequence() );
        return ruleImpl;
    }

    private void processConsequence( RuleContext ctx, Consequence consequence ) {
        ctx.getRule().setConsequence( new LambdaConsequence( consequence, ctx ) );

        Variable[] consequenceVars = consequence.getDeclarations();
        String[] requiredDeclarations = new String[consequenceVars.length];
        for (int i = 0; i < consequenceVars.length; i++) {
            requiredDeclarations[i] = consequenceVars[i].getName();
        }

        ctx.getRule().setRequiredDeclarationsForConsequence( RuleImpl.DEFAULT_CONSEQUENCE_NAME, requiredDeclarations );
    }

    private void populateLHS( RuleContext ctx, KnowledgePackageImpl pkg, View view ) {
        GroupElement lhs = ctx.getRule().getLhs();
        if (ctx.getRule().getRuleUnitClassName() != null) {
            lhs.addChild( addUnitPattern( ctx, pkg, view ) );
        }
        view.getSubConditions().forEach( condition -> lhs.addChild( conditionToElement(ctx, condition) ) );
    }

    private Pattern addUnitPattern( RuleContext ctx, KnowledgePackageImpl pkg, View view ) {
        Pattern unitPattern = addPatternForVariable( ctx, getUnitVariable( ctx, pkg, view ) );
        unitPattern.setSource( new EntryPointId( RuleUnitUtil.RULE_UNIT_ENTRY_POINT ) );
        return unitPattern;
    }

    private Variable getUnitVariable( RuleContext ctx, KnowledgePackageImpl pkg, View view ) {
        String unitClassName = ctx.getRule().getRuleUnitClassName();
        for (Variable<?> var : view.getBoundVariables()) {
            if (var.getType().asClass().getName().equals( unitClassName )) {
                return var;
            }
        }
        try {
            return variableOf( type( pkg.getTypeResolver().resolveType( unitClassName ) ) );
        } catch (ClassNotFoundException e) {
            throw new RuntimeException( e );
        }
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
                Pattern pattern = new Pattern( 0, getObjectType( Object.class ) );
                pattern.setSource( buildAccumulate( (AccumulatePattern) condition, source, pattern ) );
                return pattern;
            }
            case OOPATH: {
                OOPath ooPath = (OOPath) condition;
                Pattern pattern = buildPattern( ctx, ooPath.getFirstCondition() );
                pattern.setSource( new EntryPointId( ctx.getRule().getRuleUnitClassName() + "." + ooPath.getSource().getName() ) );
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

    private Accumulate buildAccumulate( AccumulatePattern accPattern, Pattern source, Pattern pattern ) {
        AccumulateFunction<?, ?, ?>[] accFunc = accPattern.getFunctions();

        if (accFunc.length == 1) {
            pattern.addDeclaration( new Declaration(accPattern.getBoundVariables()[0].getName(),
                                                    getReadAcessor( getObjectType( Object.class ) ),
                                                    pattern,
                                                    true) );
            return new SingleAccumulate( source, new Declaration[0], new LambdaAccumulator( accPattern.getFunctions()[0]));
        }

        InternalReadAccessor reader = new SelfReferenceClassFieldReader( Object[].class );
        Accumulator[] accumulators = new Accumulator[accFunc.length];
        for (int i = 0; i < accPattern.getFunctions().length; i++) {
            Variable accVar = accPattern.getBoundVariables()[i];
            pattern.addDeclaration( new Declaration(accVar.getName(),
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
                                       getObjectType( patternClass ),
                                       patternVariable.getName(),
                                       true );
        ctx.registerPattern( patternVariable, pattern );
        return pattern;
    }

    private void addConstraintsToPattern( RuleContext ctx, Pattern pattern, org.drools.model.Pattern modelPattern, Constraint constraint ) {
        if (constraint.getType() == Constraint.Type.SINGLE) {
            SingleConstraint singleConstraint = (SingleConstraint) constraint;
            Declaration[] declarations = getRequiredDeclaration(ctx, singleConstraint);

            if (singleConstraint.getVariables().length > 0) {
                ConstraintEvaluator constraintEvaluator = singleConstraint.isTemporal() ?
                                                          new TemporalConstraintEvaluator( declarations, pattern, singleConstraint ) :
                                                          new ConstraintEvaluator( declarations, pattern, singleConstraint );
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
            watchlist.addAll( Arrays.asList( fields ) );
        }
    }

    private Declaration[] getRequiredDeclaration( RuleContext ctx, SingleConstraint singleConstraint ) {
        Variable[] vars = singleConstraint.getVariables();
        Declaration[] declarations = new Declaration[vars.length];
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isFact()) {
                declarations[i] = ctx.getPattern( vars[i] ).getDeclaration();
            } else {
                Global global = ( (Global) vars[i] );
                ClassObjectType objectType = getObjectType( global.getType().asClass() );
                InternalReadAccessor globalExtractor = new GlobalExtractor( global.getName(), objectType);
                declarations[i] = new Declaration( global.getName(), globalExtractor, new Pattern( 0, objectType ) );
            }
        }
        return declarations;
    }

    public Collection<KiePackage> getKnowledgePackages() {
        return packages.values();
    }

    private Map<Class<?>, ClassObjectType> objectTypeCache = new HashMap<>();
    private ClassObjectType getObjectType( Class<?> patternClass ) {
        return objectTypeCache.computeIfAbsent( patternClass, c -> new ClassObjectType( c, isEvent( c ) ) );
    }

    private boolean isEvent( Class<?> patternClass ) {
        Role role = patternClass.getAnnotation( Role.class );
        return role != null && role.value() == Type.EVENT;
    }
}
