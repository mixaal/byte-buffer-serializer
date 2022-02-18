package net.mikc.serializers;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

public class SerializationOrdering {
    private final Element element;
    private final Integer order;
    private final boolean isEnum;
    private final TypeMirror typeMirror;
    SerializationOrdering(Element element, Integer order, boolean isEnum, TypeMirror typeMirror) {
        this.element = element;
        this.order = order;
        this.isEnum = isEnum;
        this.typeMirror = typeMirror;
    }

    public Element getElement() {
        return element;
    }

    public Integer getOrder() {
        return order;
    }

    public boolean isEnum() {
        return isEnum;
    }

    public TypeMirror getTypeMirror() {
        return typeMirror;
    }
}
