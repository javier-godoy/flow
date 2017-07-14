/*
 * Copyright 2000-2017 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.template.model;

import java.io.Serializable;
import java.lang.reflect.Type;

import elemental.json.JsonValue;

/**
 * A {@link ModelType} implementation that wraps a model type for performing
 * type conversions on together with a {@link ModelConverter}.
 * 
 * @author Vaadin Ltd
 *
 * @param <A>
 *            application type of the converter used by this class
 * @param <M>
 *            model type of the converter used by this class
 */
public class ConvertedModelType<A, M extends Serializable>
        implements ModelType {

    private final ModelType wrappedModelType;
    private final ModelConverter<A, M> converter;

    /**
     * Creates a new ConvertedModelType from the given model type and converter.
     * 
     * @param modelType
     *            the model type to wrap
     * @param converter
     *            the converter to use
     */
    ConvertedModelType(ModelType modelType,
            ModelConverter<A, M> converter) {
        wrappedModelType = modelType;
        this.converter = converter;
    }

    @Override
    public Object modelToApplication(Serializable modelValue) {
        @SuppressWarnings("unchecked")
        M wrappedApplicationValue = (M) wrappedModelType
                .modelToApplication(modelValue);
        return converter.toApplication(wrappedApplicationValue);
    }

    @Override
    public Object modelToNashorn(Serializable modelValue) {
        throw new UnsupportedOperationException("Obsolete functionality");
    }


    @Override
    public Serializable applicationToModel(Object applicationValue,
            PropertyFilter filter) {
        @SuppressWarnings("unchecked")
        M convertedValue = converter.toModel((A) applicationValue);
        return wrappedModelType.applicationToModel(convertedValue,
                filter);
    }

    @Override
    public boolean accepts(Type applicationType) {
        return converter.getApplicationType()
                .isAssignableFrom((Class<?>) applicationType);
    }

    @Override
    public Type getJavaType() {
        return converter.getApplicationType();
    }

    @Override
    public JsonValue toJson() {
        return wrappedModelType.toJson();
    }
}