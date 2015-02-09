/*
 * Copyright 2012-2015 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marklogic.client.MarkLogicIOException;
import com.marklogic.client.io.marker.BufferableHandle;
import com.marklogic.client.io.marker.ContentHandle;
import com.marklogic.client.io.marker.ContentHandleFactory;
import com.marklogic.client.io.marker.XMLReadHandle;
import com.marklogic.client.io.marker.XMLWriteHandle;

/**
 * A JAXB Handle roundtrips a POJO (a Java data structure) to and from a database document.
 * 
 * The POJO class must have JAXB annotations or must be generated by xjc from an XML Schema.
 * 
 * The JAXB Handle must be initialized with a JAXB Context with which the root POJO classes
 * have been registered.
 * 
 * @param	<C>	one of the classes (or the &lt;?&gt; wildcard for any of the classes) registered with the JAXB Context
 */
public class JAXBHandle<C>
	extends BaseHandle<InputStream, OutputStreamSender>
    implements OutputStreamSender, BufferableHandle, ContentHandle<C>,
        XMLReadHandle, XMLWriteHandle
{
	static final private Logger logger = LoggerFactory.getLogger(JAXBHandle.class);

	private JAXBContext  context;
	private Unmarshaller unmarshaller;
	private Marshaller   marshaller;
	private C            content;

	/**
	 * Creates a factory to create a JAXBHandle instance for POJO instances
	 * of the specified classes.
	 * @param pojoClasses	the POJO classes for which this factory provides a handle
	 * @return	the factory
	 */
	static public ContentHandleFactory newFactory(Class<?>... pojoClasses)
	throws JAXBException {
		if (pojoClasses == null || pojoClasses.length == 0)
			return null;
		return new JAXBHandleFactory(pojoClasses);
	}
	/**
	 * Creates a factory to create a JAXBHandle instance for POJO instances
	 * of the specified classes.
	 * @param context	the JAXB context for marshaling the POJO classes
	 * @param pojoClasses	the POJO classes for which this factory provides a handle
	 * @return	the factory
	 */
	static public ContentHandleFactory newFactory(JAXBContext context, Class<?>... pojoClasses)
	throws JAXBException {
		if (context == null || pojoClasses == null || pojoClasses.length == 0)
			return null;
		return new JAXBHandleFactory(context, pojoClasses);
	}

	/**
	 * Initializes the JAXB handle with the JAXB context for the classes
	 * of the marshalled or unmarshalled structure.
	 * @param context	the JAXB context
	 */
	public JAXBHandle(JAXBContext context) {
		super();
		if (context == null) {
			throw new IllegalArgumentException(
					"null JAXB context for converting classes"
					);
		}
		super.setFormat(Format.XML);
   		setResendable(true);
		this.context = context;
	}

	/**
	 * Returns the root object of the JAXB structure for the content.
	 * @return	the root JAXB object
	 */
	@Override
	public C get() {
		return content;
	}
	/**
	 * Returns the root object of the JAXB structure for the content
	 * cast to a more specific class.
	 * @param as	the class of the object
	 * @return	the root JAXB object
	 */
	public <T> T get(Class<T> as) {
		if (content == null) {
			return null;
		}
		if (as == null) {
			throw new IllegalArgumentException("Cannot cast content to null class");
		}
		if (!as.isAssignableFrom(content.getClass())) {
			throw new IllegalArgumentException(
					"Cannot cast "+content.getClass().getName()+" to "+as.getName()
					);
		}
		@SuppressWarnings("unchecked")
		T content = (T) get();
		return content;
	}
	/**
	 * Assigns the root object of the JAXB structure for the content.
	 * @param content	the root JAXB object
	 */
	@Override
    public void set(C content) {
    	this.content = content;
    }
    /**
	 * Assigns the root object of the JAXB structure for the content
	 * and returns the handle as a fluent convenience.
	 * @param content	the root JAXB object
	 * @return	this handle
     */
    public JAXBHandle<C> with(C content) {
    	set(content);
    	return this;
    }

	/**
	 * Restricts the format to XML.
	 */
	@Override
    public void setFormat(Format format) {
		if (format != Format.XML)
			throw new IllegalArgumentException("JAXBHandle supports the XML format only");
	}
	/**
	 * Specifies the mime type of the content and returns the handle
	 * as a fluent convenience.
	 * @param mimetype	the mime type of the content
	 * @return	this handle
	 */
	public JAXBHandle<C> withMimetype(String mimetype) {
		setMimetype(mimetype);
		return this;
	}

	/**
     * fromBuffer() unmarshals a JAXB POJO from a byte array
     * buffer.  The buffer must store the marshaled XML for the 
     * JAXB POJO in UTF-8 encoding. JAXB cannot unmarshal arbitrary XML.
	 */
	@Override
	public void fromBuffer(byte[] buffer) {
		if (buffer == null || buffer.length == 0)
			content = null;
		else
			receiveContent(new ByteArrayInputStream(buffer));
	}
	@Override
	public byte[] toBuffer() {
		try {
			if (content == null)
				return null;

			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			write(buffer);

			return buffer.toByteArray();
		} catch (IOException e) {
			throw new MarkLogicIOException(e);
		}
	}
	/**
	 * Returns the JAXB structure as an XML string.
	 */
	@Override
	public String toString() {
		try {
			return new String(toBuffer(),"UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new MarkLogicIOException(e);
		}
	}

	/**
	 * Returns the unmarshaller that converts a tree data structure
	 * from XML to Java objects, reusing any existing unmarshaller.
	 * @return	the unmarshaller for the JAXB context
	 */
	public Unmarshaller getUnmarshaller()
	throws JAXBException {
		return getUnmarshaller(true);
	}
	/**
	 * Returns the unmarshaller that converts a tree data structure
	 * from XML to Java objects.
	 * @param reuse	whether to reuse an existing unmarshaller
	 * @return	the unmarshaller for the JAXB context
	 */
	public Unmarshaller getUnmarshaller(boolean reuse)
	throws JAXBException {
		if (!reuse || unmarshaller == null) {
			unmarshaller = context.createUnmarshaller();
		}
		return unmarshaller;
	}
	/**
	 * Returns the marshaller that converts a tree data structure
	 * from Java objects to XML, reusing any existing marshaller.
	 * @return	the marshaller for the JAXB context
	 */
	public Marshaller getMarshaller()
	throws JAXBException {
		return getMarshaller(true);
	}
	/**
	 * Returns the marshaller that converts a tree data structure
	 * from Java objects to XML.
	 * @param reuse	whether to reuse an existing marshaller
	 * @return	the marshaller for the JAXB context
	 */
	public Marshaller getMarshaller(boolean reuse)
	throws JAXBException {
		if (!reuse || this.marshaller == null) {
			Marshaller marshaller = context.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
			marshaller.setProperty(Marshaller.JAXB_ENCODING,         "UTF-8");
			this.marshaller = marshaller;
		}
		return this.marshaller;
	}

	@Override
	protected Class<InputStream> receiveAs() {
    	return InputStream.class;
    }
    @Override
	protected void receiveContent(InputStream content) {
		try {
			@SuppressWarnings("unchecked")
			C unmarshalled = (C) getUnmarshaller().unmarshal(
					new InputStreamReader(content, "UTF-8")
					);
			this.content = unmarshalled;
		} catch (JAXBException e) {
			logger.error("Failed to unmarshall object read from database document",e);
			throw new MarkLogicIOException(e);
		} catch (UnsupportedEncodingException e) {
			logger.error("Failed to unmarshall object read from database document",e);
			throw new MarkLogicIOException(e);
		}  finally {
			try {
				content.close();
			} catch (IOException e) {
				// ignore.
			}
		}
	}
    @Override
	protected OutputStreamSender sendContent() {
		if (content == null) {
			throw new IllegalStateException("No object to write");
		}

		return this;
	}

	@Override
	public void write(OutputStream out) throws IOException {
		try {
			getMarshaller().marshal(content, out);
		} catch (JAXBException e) {
			logger.error("Failed to marshall object for writing to database document",e);
			throw new MarkLogicIOException(e);
		}
	}

	static private class JAXBHandleFactory implements ContentHandleFactory {
		private Class<?>[]    pojoClasses;
		private JAXBContext   factoryContext;
		private Set<Class<?>> classSet;

		private JAXBHandleFactory(Class<?>... pojoClasses)
		throws JAXBException {
			this(JAXBContext.newInstance(pojoClasses), pojoClasses);
		}
		private JAXBHandleFactory(JAXBContext factoryContext, Class<?>... pojoClasses)
		throws JAXBException {
			super();
			this.pojoClasses    = pojoClasses;
			this.factoryContext = factoryContext;
			this.classSet       = new HashSet<Class<?>>(Arrays.asList(pojoClasses));
		}

		@Override
		public Class<?>[] getHandledClasses() {
			return pojoClasses;
		}
		@Override
		public boolean isHandled(Class<?> type) {
			return classSet.contains(type);
		}
		@Override
		public <C> ContentHandle<C> newHandle(Class<C> type) {
			ContentHandle<C> handle = isHandled(type) ?
					(ContentHandle<C>) new JAXBHandle<C>(factoryContext) : null;
			return handle;
		}
	}
}
