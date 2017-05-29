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

import org.drools.compiler.kproject.models.KieBaseModelImpl;
import org.drools.core.RuleBaseConfiguration;
import org.drools.core.impl.InternalKnowledgeBase;
import org.drools.model.Model;
import org.kie.api.KieBaseConfiguration;
import org.kie.internal.KnowledgeBaseFactory;
import org.kie.internal.definition.KnowledgePackage;

public class KieBaseBuilder {

    public static InternalKnowledgeBase createKieBase( Model model ) {
        return createKieBase( model, null, KieBaseBuilder.class.getClassLoader(), null );
    }

    public static InternalKnowledgeBase createKieBase( Model model, KieBaseModelImpl kBaseModel, ClassLoader cl, KieBaseConfiguration conf ) {
        if (conf == null) {
            conf = getKnowledgeBaseConfiguration(kBaseModel, cl);
        } else if (conf instanceof RuleBaseConfiguration ) {
            ((RuleBaseConfiguration)conf).setClassLoader(cl);
        }

        KnowledgePackagesBuilder builder = new KnowledgePackagesBuilder(conf);
        builder.addModel(model);

        String kBaseName = kBaseModel != null ? kBaseModel.getName() : "defaultkiebase";

        Collection<KnowledgePackage> pkgs = builder.getKnowledgePackages();
        InternalKnowledgeBase kBase = (InternalKnowledgeBase) KnowledgeBaseFactory.newKnowledgeBase( kBaseName, conf );
        builder.getPatternClasses().forEach( kBase::getOrCreateExactTypeDeclaration );
        kBase.addKnowledgePackages( pkgs );
        return kBase;
    }

    private static KieBaseConfiguration getKnowledgeBaseConfiguration( KieBaseModelImpl kBaseModel, ClassLoader cl ) {
        KieBaseConfiguration kbConf = KnowledgeBaseFactory.newKnowledgeBaseConfiguration(null, cl);
        if (kBaseModel != null) {
            kbConf.setOption( kBaseModel.getEqualsBehavior() );
            kbConf.setOption( kBaseModel.getEventProcessingMode() );
            kbConf.setOption( kBaseModel.getDeclarativeAgenda() );
        }
        return kbConf;
    }
}
