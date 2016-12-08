/**********************************************************************
Copyright (c) 2004 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
2004 Erik Bengtson - add dependent elements
    ...
**********************************************************************/
package org.datanucleus.metadata;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Set;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.ClassNotResolvedException;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Representation of the MetaData of a collection.
 */
public class CollectionMetaData extends ContainerMetaData
{
    private static final long serialVersionUID = -5567408442228331561L;

    /** Representation of the element of the collection. */
    protected ContainerComponent element;

    /**
     * Constructor to create a copy of the passed metadata.
     * @param collmd The metadata to copy
     */
    public CollectionMetaData(CollectionMetaData collmd)
    {
        super(collmd);
        element = new ContainerComponent();
        element.embedded = collmd.element.embedded;
        element.serialized = collmd.element.serialized;
        element.dependent = collmd.element.dependent;
        element.type = collmd.element.type;
        element.classMetaData = collmd.element.classMetaData;
    }

    /**
     * Default constructor. Set the fields using setters, before populate().
     */
    public CollectionMetaData()
    {
        element = new ContainerComponent();
    }

    /**
     * Method to populate any defaults, and check the validity of the MetaData.
     * @param clr ClassLoaderResolver to use for any loading operations
     * @param primary the primary ClassLoader to use (or null)
     * @param mmgr MetaData manager
     */
    public void populate(ClassLoaderResolver clr, ClassLoader primary, MetaDataManager mmgr)
    {
        AbstractMemberMetaData mmd = (AbstractMemberMetaData)parent;
        if (!StringUtils.isWhitespace(element.type) && element.type.indexOf(',') > 0)
        {
            throw new InvalidMemberMetaDataException("044131", mmd.getClassName(), mmd.getName());
        }

        // Make sure the type in "element" is set
        element.populate(((AbstractMemberMetaData)parent).getAbstractClassMetaData().getPackageName(), 
            clr, primary, mmgr);

        // Check the field type and see if it is castable to a Collection
        Class field_type = getMemberMetaData().getType();
        if (!java.util.Collection.class.isAssignableFrom(field_type))
        {
            throw new InvalidMemberMetaDataException("044132", mmd.getClassName(), mmd.getName());
        }

        // "element-type"
        if (element.type == null)
        {
            throw new InvalidMemberMetaDataException("044133",  mmd.getClassName(), mmd.getName());
        }

        // Check that the element type exists
        Class elementTypeClass = null;
        try
        {
            elementTypeClass = clr.classForName(element.type, primary);
        }
        catch (ClassNotResolvedException cnre)
        {
            throw new InvalidMemberMetaDataException("044134", getMemberMetaData().getClassName(), getFieldName(), element.type);
        }

        if (!elementTypeClass.getName().equals(element.type))
        {
            // The element-type has been resolved from what was specified in the MetaData - update to the fully-qualified name
            NucleusLogger.METADATA.info(Localiser.msg("044135", getFieldName(), getMemberMetaData().getClassName(false), element.type, elementTypeClass.getName()));
            element.type = elementTypeClass.getName();
        }

		// "embedded-element"
        if (element.embedded == null)
        {
            // Assign default for "embedded-element" based on 18.13.1 of JDO 2 spec
            if (mmgr.getNucleusContext().getTypeManager().isDefaultEmbeddedType(elementTypeClass))
            {
                element.embedded = Boolean.TRUE;
            }
            else
            {
                // Use "readMetaDataForClass" in case we havent yet initialised the metadata for the element
                AbstractClassMetaData elemCmd = mmgr.readMetaDataForClass(elementTypeClass.getName());
                if (elemCmd == null)
                {
                    // Try to load it just in case using annotations and only pulled in one side of the relation
                    try
                    {
                        elemCmd = mmgr.getMetaDataForClass(elementTypeClass, clr);
                    }
                    catch (Throwable thr)
                    {
                    }
                }
                if (elemCmd != null)
                {
                    element.embedded = (elemCmd.isEmbeddedOnly() ? Boolean.TRUE : Boolean.FALSE);
                }
                else if (elementTypeClass.isInterface() || elementTypeClass == Object.class)
                {
                    // Collection<interface> or Object not explicitly marked as embedded defaults to false
                    element.embedded = Boolean.FALSE;
                }
                else
                {
                    // Fallback to true
                    NucleusLogger.METADATA.debug("Member with collection of elementType=" + elementTypeClass.getName()+
                        " not explicitly marked as embedded, so defaulting to embedded since not persistable");
                    element.embedded = Boolean.TRUE;
                }
            }
        }
        else if (Boolean.FALSE.equals(element.embedded))
        {
            // Use "readMetaDataForClass" in case we havent yet initialised the metadata for the element
            AbstractClassMetaData elemCmd = mmgr.readMetaDataForClass(elementTypeClass.getName());
            if (elemCmd == null && !elementTypeClass.isInterface() && elementTypeClass != java.lang.Object.class)
            {
                // If the user has set a non-PC/non-Interface as not embedded, correct it since not supported.
                // Note : this fails when using in the enhancer since not yet PC
                NucleusLogger.METADATA.debug("Member with collection of element type " + elementTypeClass.getName() +
                    " marked as not embedded, but only persistable as embedded, so resetting");
                element.embedded = Boolean.TRUE;
            }
        }

        ElementMetaData elemmd = ((AbstractMemberMetaData)parent).getElementMetaData();
        if (elemmd != null && elemmd.getEmbeddedMetaData() != null)
        {
            element.embedded = Boolean.TRUE;
        }

        if (Boolean.TRUE.equals(element.dependent))
        {
            // If the user has set a non-PC/non-reference as dependent, correct it since not valid.
            // Note : this fails when using in the enhancer since not yet PC
            if (!mmgr.getApiAdapter().isPersistable(elementTypeClass) && !elementTypeClass.isInterface() && elementTypeClass != java.lang.Object.class)
            {
                element.dependent = Boolean.FALSE;
            }
        }

        // Keep a reference to the MetaData for the element
        element.classMetaData = mmgr.getMetaDataForClassInternal(elementTypeClass, clr);

        if (hasExtension("implementation-classes"))
        {
            // Check/fix the validity of the implementation-classes and qualify them where required.
            StringBuilder str = new StringBuilder();
            String[] implTypes = getValuesForExtension("implementation-classes");
            for (int i=0;i<implTypes.length;i++)
            {
                String implTypeName = ClassUtils.createFullClassName(getMemberMetaData().getPackageName(), implTypes[i]);
                if (i > 0)
                {
                    str.append(",");
                }

                try
                {
                    clr.classForName(implTypeName);
                    str.append(implTypeName);
                }
                catch (ClassNotResolvedException cnre)
                {
                    try
                    {
                        // Maybe the user specified a java.lang class without fully-qualifying it
                        // This is beyond the scope of the JDO spec which expects java.lang cases to be fully-qualified
                        String langClassName = ClassUtils.getJavaLangClassForType(implTypeName);
                        clr.classForName(langClassName);
                        str.append(langClassName);
                    }
                    catch (ClassNotResolvedException cnre2)
                    {
                        // Implementation type not found
                        throw new InvalidMemberMetaDataException("044116", getMemberMetaData().getClassName(), getMemberMetaData().getName(), implTypes[i]);
                    }
                }
            }
            addExtension(VENDOR_NAME, "implementation-classes", str.toString()); // Replace with this new value
        }

        // Make sure anything in the superclass is populated too
        super.populate(clr, primary, mmgr);

        setPopulated();
    }

    /**
     * Accessor for the element-type tag value.
     * This can contain comma-separated values.
     * @return element-type tag value
     */
    public String getElementType()
    {
        return element.type;
    }

    public String[] getElementTypes()
    {
        return ((AbstractMemberMetaData)getParent()).getValuesForExtension("implementation-classes");
    }

    public boolean elementIsPersistent()
    {
        return element.classMetaData != null;
    }

    /**
     * Convenience accessor for the Element ClassMetaData.
     * @param clr ClassLoader resolver (in case we need to initialise it)
     * @param mmgr MetaData manager
     * @return element ClassMetaData
     */
    public AbstractClassMetaData getElementClassMetaData(final ClassLoaderResolver clr, final MetaDataManager mmgr)
    {
        if (element.classMetaData != null && !element.classMetaData.isInitialised())
        {
            // Do as PrivilegedAction since uses reflection
            // [JDOAdapter.isValidPrimaryKeyClass calls reflective methods]
            AccessController.doPrivileged(new PrivilegedAction()
            {
                public Object run()
                {
                    element.classMetaData.initialise(clr, mmgr);
                    return null;
                }
            });
        }
        return element.classMetaData;
    }

    /**
     * Accessor for the embedded-element tag value
     * @return embedded-element tag value
     */
    public boolean isEmbeddedElement()
    {
        if (element.embedded == null)
        {
            return false;
        }
        return element.embedded.booleanValue();
    }

    /**
     * Accessor for The dependent-element attribute indicates that the
     * collection's element contains a reference that is to be deleted if the
     * referring instance is deleted.
     * 
     * @return dependent-element tag value
     */
    public boolean isDependentElement()
    {
        if (element.dependent == null)
        {
            return false;
        }
        else if (element.classMetaData == null)
        {
            return false;
        }
        else
        {
            return element.dependent.booleanValue();
        }
    }

    /**
     * Accessor for the serialized-element tag value
     * @return serialized-element tag value
     */
    public boolean isSerializedElement()
    {
        if (element.serialized == null)
        {
            return false;
        }
        return element.serialized.booleanValue();
    }

    public CollectionMetaData setElementType(String type)
    {
        element.setType(type);
        return this;
    }

    public CollectionMetaData setEmbeddedElement(boolean embedded)
    {
        element.setEmbedded(embedded);
        return this;
    }

    public CollectionMetaData setSerializedElement(boolean serialized)
    {
        element.setSerialized(serialized);
        return this;
    }

    public CollectionMetaData setDependentElement(boolean dependent)
    {
        element.setDependent(dependent);
        return this;
    }

    // ------------------------------- Utilities -------------------------------

    /**
     * Accessor for all ClassMetaData referenced by this array.
     * @param orderedCMDs List of ordered ClassMetaData objects (added to).
     * @param referencedCMDs Set of all ClassMetaData objects (added to).
     * @param clr the ClassLoaderResolver
     * @param mmgr MetaData manager
     */
    void getReferencedClassMetaData(final List orderedCMDs, final Set referencedCMDs,
            final ClassLoaderResolver clr, final MetaDataManager mmgr)
    { 
        AbstractClassMetaData element_cmd = mmgr.getMetaDataForClass(element.type, clr);
        if (element_cmd != null)
        {
            element_cmd.getReferencedClassMetaData(orderedCMDs, referencedCMDs, clr, mmgr);
        }
    }

    /**
     * Returns a string representation of the object.
     * @param prefix prefix string
     * @param indent indent string
     * @return a string representation of the object.
     */
    public String toString(String prefix,String indent)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("<collection element-type=\"").append(element.type).append("\"");
        if (element.embedded != null)
        {
            sb.append(" embedded-element=\"").append(element.embedded).append("\"");
        }
        if (element.dependent != null)
        {
            sb.append(" dependent-element=\"").append(element.dependent).append("\"");
        }
        if (element.serialized != null)
        {
            sb.append(" serialized-element=\"").append(element.serialized).append("\"");
        }
        sb.append(">\n");

        // Add extensions
        sb.append(super.toString(prefix + indent,indent)); 
 
        sb.append(prefix).append("</collection>\n");
        return sb.toString();
    }
}