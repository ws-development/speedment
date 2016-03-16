/**
 *
 * Copyright (c) 2006-2016, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.internal.core.code.entity;

import com.speedment.Entity;
import com.speedment.Speedment;
import com.speedment.config.db.Dbms;
import com.speedment.config.db.Table;
import com.speedment.field.FieldIdentifier;
import com.speedment.codegen.base.Generator;
import com.speedment.codegen.lang.models.Constructor;
import com.speedment.codegen.lang.models.Enum;
import com.speedment.codegen.lang.models.EnumConstant;
import com.speedment.codegen.lang.models.Field;
import com.speedment.codegen.lang.models.File;
import com.speedment.codegen.lang.models.Generic;
import com.speedment.codegen.lang.models.Import;
import com.speedment.codegen.lang.models.Interface;
import com.speedment.codegen.lang.models.Javadoc;
import com.speedment.codegen.lang.models.Method;
import com.speedment.codegen.lang.models.Type;
import com.speedment.config.db.Index;
import com.speedment.config.db.IndexColumn;
import static com.speedment.internal.codegen.lang.models.constants.DefaultAnnotationUsage.OVERRIDE;
import static com.speedment.internal.codegen.lang.models.constants.DefaultJavadocTag.PARAM;
import static com.speedment.internal.codegen.lang.models.constants.DefaultJavadocTag.RETURN;
import static com.speedment.internal.codegen.lang.models.constants.DefaultType.OPTIONAL;
import static com.speedment.internal.codegen.lang.models.constants.DefaultType.STRING;
import com.speedment.internal.codegen.lang.models.implementation.GenericImpl;
import com.speedment.internal.codegen.lang.models.values.ReferenceValue;
import com.speedment.internal.codegen.lang.models.values.TextValue;
import static com.speedment.internal.codegen.util.Formatting.shortName;
import static com.speedment.internal.core.code.DefaultJavaClassTranslator.GETTER_METHOD_PREFIX;
import static com.speedment.internal.core.code.DefaultJavaClassTranslator.SETTER_METHOD_PREFIX;
import com.speedment.internal.core.code.EntityAndManagerTranslator;
import com.speedment.internal.util.document.DocumentDbUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import static com.speedment.internal.util.document.DocumentUtil.relativeName;

/**
 *
 * @author Emil Forslund
 * @author Per-Åke Minborg
 */
public final class GeneratedEntityTranslator extends EntityAndManagerTranslator<Interface> {

    public GeneratedEntityTranslator(Speedment speedment, Generator gen, Table table) {
        super(speedment, gen, table, Interface::of);
    }

    @Override
    protected Interface makeCodeGenModel(File file) {
        final Map<Table, List<String>> fkStreamers = new HashMap<>();

        final Enum identifier = Enum.of("Identifier")
            .add(Field.of("columnName", STRING).private_().final_())
            .add(Type.of(FieldIdentifier.class))
            .add(Constructor.of()
                .add(Field.of("columnName", STRING))
                .add("this.columnName = columnName;")
            )
            .add(Method.of("dbmsName", STRING).public_()
                .add(OVERRIDE)
                .add("return \"" + dbmsOrThrow().getName() + "\";")
            )
            .add(Method.of("schemaName", STRING).public_()
                .add(OVERRIDE)
                .add("return \"" + schemaOrThrow().getName() + "\";")
            )
            .add(Method.of("tableName", STRING).public_()
                .add(OVERRIDE)
                .add("return \"" + tableOrThrow().getName() + "\";")
            )
            .add(Method.of("columnName", STRING).public_()
                .add(OVERRIDE)
                .add("return this.columnName;")
            );

        final Interface iface = newBuilder(file, entity.getGeneratedName())
            /*** Getters ***/
            .forEveryColumn((clazz, col) -> {
                final Type retType = col.isNullable()
                    ? OPTIONAL.add(
                        Generic.of().add(
                            Type.of(col.findTypeMapper().getJavaType())
                        )
                    )
                    : Type.of(col.findTypeMapper().getJavaType());
                
                clazz.add(Method.of(GETTER_METHOD_PREFIX + typeName(col), retType)
                    .set(Javadoc.of(
                            "Returns the " + variableName(col) + 
                            " of this " + entity.getName() + 
                            ". The " + variableName(col) + 
                            " field corresponds to the database column " +
                            relativeName(col, Dbms.class) + "."
                        ).add(RETURN.setText(
                            "the " + variableName(col) + 
                            " of this " + entity.getName()
                        ))
                    )
                );
            })
            
            /*** Setters ***/
            .forEveryColumn((clazz, col) -> {
                clazz.add(Method.of(SETTER_METHOD_PREFIX + typeName(col), entity.getType())
                    .add(Field.of(variableName(col), Type.of(col.findTypeMapper().getJavaType())))
                    .set(Javadoc.of(
                        "Sets the " + variableName(col) + 
                        " of this " + entity.getName() + 
                        ". The " + variableName(col) + 
                        " field corresponds to the database column " +
                        relativeName(col, Dbms.class) + "."
                    )
                    .add(PARAM.setValue(variableName(col)).setText("to set of this " + entity.getName()))
                    .add(RETURN.setText("this " + entity.getName() + " instance")))
                );
            })
            
            /*** Fields ***/
            .forEveryColumn((clazz, col) -> {
                final EntityTranslatorSupport.ReferenceFieldType ref = 
                    EntityTranslatorSupport.getReferenceFieldType(
                        file, tableOrThrow(), col, entity.getType(), javaLanguageNamer()
                    );

                final String typeMapper      = col.getTypeMapper();
                final Type entityType        = entity.getType();
                final String shortEntityName = entity.getName();
                final Type typeMapperType    = Type.of(typeMapper);

                file.add(Import.of(entityType));
                file.add(Import.of(typeMapperType));

                final String getter, finder;
                if (col.isNullable()) {
                    getter = "o -> o.get" + typeName(col) + "().orElse(null)";
                    finder = EntityTranslatorSupport.getForeignKey(tableOrThrow(), col)
                        .map(fkc -> 
                            ", fk -> fk.find" +
                            javaLanguageNamer().javaTypeName(col.getJavaName()) +
                            "().orElse(null)"
                        ).orElse("");
                } else {
                    getter = shortEntityName + "::get" + typeName(col);
                    finder = EntityTranslatorSupport.getForeignKey(tableOrThrow(), col)
                        .map(fkc -> 
                            ", " + shortEntityName + "::find" +
                            javaLanguageNamer().javaTypeName(col.getJavaName())

                        ).orElse("");
                }
                final String setter = ", " + shortEntityName + "::set" + typeName(col);

                final String constant = javaLanguageNamer().javaStaticFieldName(col.getJavaName());
                identifier.add(EnumConstant.of(constant).add(new TextValue(col.getName())));

                file.add(Import.of(ref.implType));
                clazz.add(Field.of(javaLanguageNamer().javaStaticFieldName(col.getJavaName()), ref.type)
                        .final_()
                        .set(new ReferenceValue(
                            "new " + shortName(ref.implType.getName())
                            + "<>(Identifier."
                            + constant
                            + ", "
                            + getter
                            + setter
                            + finder
                            + ", new "
                            + shortName(typeMapper)
                            + "(), " 
                            + DocumentDbUtil.isUnique(col)
                            + ")"
                        ))
                        .set(Javadoc.of(
                                "This Field corresponds to the {@link " + shortEntityName + "} field that can be obtained using the "
                                + "{@link " + shortEntityName + "#get" + typeName(col) + "()} method."
                        )));
            })
            
            /*** Add streamers from back pointing FK:s ***/
            .forEveryForeignKeyReferencingThis((i, fk) -> {
                final FkHolder fu = new FkHolder(getSpeedment(), getCodeGenerator(), fk);
                file.add(Import.of(fu.getEmt().entity().getType()));

                Import imp = Import.of(fu.getEmt().entity().getType());
                file.add(imp);

                fu.imports().forEachOrdered(file::add);
                final String methodName = EntityTranslatorSupport.FIND
                    + EntityTranslatorSupport.pluralis(fu.getTable(), javaLanguageNamer())
                    + "By" + typeName(fu.getColumn());
                
                /*** Record for later use in the construction of aggregate streamers ***/
                fkStreamers.computeIfAbsent(fu.getTable(), t -> new ArrayList<>()).add(methodName);
                final Type returnType = Type.of(Stream.class).add(fu.getEmt().genericOfEntity());
                final Method method = Method.of(methodName, returnType);

                method.set(Javadoc.of(
                        "Creates and returns a {@link Stream} of all "
                        + "{@link " + typeName(fu.getTable()) + "} Entities that references this Entity by "
                        + "the foreign key field that can be obtained using {@link " + typeName(fu.getTable()) + "#get" + typeName(fu.getColumn()) + "()}. "
                        + "The order of the Entities are undefined and may change from time to time. "
                        + "<p>\n"
                        + "Using this method, you may \"walk the graph\" and jump "
                        + "directly between referencing Entities without using {@code JOIN}s."
                        + "<p>\n"
                        + "N.B. The current implementation supports lazy-loading of the referencing Entities."
                    )
                    .add(RETURN.setText(
                        "a {@link Stream} of all "
                        + "{@link " + typeName(fu.getTable()) + "} Entities  that references this Entity by "
                        + "the foreign key field that can be obtained using {@link " + typeName(fu.getTable()) + "#get" + typeName(fu.getColumn()) + "()}")
                    )
                );

                i.add(method);
            })
            
            /*** Add ordinary finders ***/
            .forEveryForeignKey((clazz, fk) -> {

                final FkHolder fu = new FkHolder(getSpeedment(), getCodeGenerator(), fk);
                fu.imports().forEachOrdered(file::add);

                final Type returnType;
                if (fu.getColumn().isNullable()) {
                    file.add(Import.of(Type.of(Optional.class)));
                    returnType = Type.of(Optional.class).add(fu.getForeignEmt().genericOfEntity());

                } else {
                    returnType = fu.getForeignEmt().entity().getType();
                }

                final Method method = Method.of("find" + typeName(fu.getColumn()), returnType);

                final String returns = 
                    "the foreign key Entity {@link " + 
                    typeName(fu.getForeignTable()) + "} referenced " +
                    "by the field that can be obtained using {@link " + 
                    entity.getName() + "#get" + 
                    typeName(fu.getColumn()) + "()}";

                method.set(Javadoc.of(
                        "Finds and returns " + returns + ".\n<p>\n" +
                        "N.B. The current implementation only supports lazy-loading " +
                        "of the referenced Entities. This means that if you " +
                        "traverse N " + entity.getName() + " entities and call this " +
                        "method for each one, there will be N SQL-queries executed."
                    ).add(RETURN.setText(returns)
                ));

                clazz.add(method);
            })
            .build()
            .public_()
            .add(identifier)
            .add(Type.of(Entity.class).add(Generic.of().add(entity.getType())));
        
        

        /*** Create aggregate streaming functions, if any ***/
        fkStreamers.keySet().stream().forEach((referencingTable) -> {
            final List<String> methodNames = fkStreamers.get(referencingTable);
            if (!methodNames.isEmpty()) {
                final Method method = Method.of(EntityTranslatorSupport.FIND + EntityTranslatorSupport.pluralis(referencingTable,
                    javaLanguageNamer()), Type.of(Stream.class).add(new GenericImpl(typeName(referencingTable))));

                method.set(Javadoc.of(
                        "Creates and returns a <em>distinct</em> {@link Stream} of all " +
                        "{@link " + typeName(referencingTable) + "} Entities that "+
                        "references this Entity by a foreign key. The order of the "+
                        "Entities are undefined and may change from time to time.\n"+
                        "<p>\n"+
                        "Note that the Stream is <em>distinct</em>, meaning that " +
                        "referencing Entities will only appear once in the Stream, even " +
                        "though they may reference this Entity by several columns.\n" +
                        "<p>\n" +
                        "Using this method, you may \"walk the graph\" and jump " +
                        "directly between referencing Entities without using {@code JOIN}s.\n" +
                        "<p>\n" +
                        "N.B. The current implementation supports lazy-loading of the referencing Entities."
                    ).add(RETURN.setText(
                        "a <em>distinct</em> {@link Stream} of all {@link " + 
                        typeName(referencingTable) + "} " +
                        "Entities that references this Entity by a foreign key"
                    ))
                );

                iface.add(method);
            }
        });

        return iface;
    }

    @Override
    protected String getJavadocRepresentText() {
        return "The generated base interface";
    }

    @Override
    protected String getClassOrInterfaceName() {
        return entity.getGeneratedName();
    }

    @Override
    public boolean isInGeneratedPackage() {
        return true;
    }
}