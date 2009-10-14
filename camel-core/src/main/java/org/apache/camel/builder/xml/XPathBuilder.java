/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.builder.xml;

import java.io.InputStream;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;
import javax.xml.xpath.XPathFunction;
import javax.xml.xpath.XPathFunctionException;
import javax.xml.xpath.XPathFunctionResolver;

import org.apache.camel.component.file.GenericFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.InputSource;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Message;
import org.apache.camel.Predicate;
import org.apache.camel.RuntimeExpressionException;
import org.apache.camel.Service;
import org.apache.camel.spi.NamespaceAware;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.MessageHelper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static org.apache.camel.builder.xml.Namespaces.DEFAULT_NAMESPACE;
import static org.apache.camel.builder.xml.Namespaces.IN_NAMESPACE;
import static org.apache.camel.builder.xml.Namespaces.OUT_NAMESPACE;
import static org.apache.camel.builder.xml.Namespaces.isMatchingNamespaceOrEmptyNamespace;

/**
 * Creates an XPath expression builder which creates a nodeset result by default.
 * If you want to evaluate a String expression then call {@link #stringResult()}
 * <p/>
 * An XPath object is not thread-safe and not reentrant. In other words, it is the application's responsibility to make
 * sure that one XPath object is not used from more than one thread at any given time, and while the evaluate method
 * is invoked, applications may not recursively call the evaluate method.
 * <p/>
 * This implementation is thread safe by using thread locals and pooling to allow concurrency
 *
 * @see XPathConstants#NODESET
 *
 * @version $Revision$
 */
public class XPathBuilder implements Expression, Predicate, NamespaceAware, Service {
    private static final transient Log LOG = LogFactory.getLog(XPathBuilder.class);
    private final Queue<XPathExpression> pool = new ConcurrentLinkedQueue<XPathExpression>();
    private final String text;
    private final ThreadLocal<MessageVariableResolver> variableResolver = new ThreadLocal<MessageVariableResolver>();
    private final ThreadLocal<Exchange> exchange = new ThreadLocal<Exchange>();

    private XPathFactory xpathFactory;
    private Class<?> documentType = Document.class;
    // For some reason the default expression of "a/b" on a document such as
    // <a><b>1</b><b>2</b></a>
    // will evaluate as just "1" by default which is bizarre. So by default
    // lets assume XPath expressions result in nodesets.
    private Class<?> resultType;
    private QName resultQName = XPathConstants.NODESET;
    private String objectModelUri;
    private DefaultNamespaceContext namespaceContext;
    private XPathFunctionResolver functionResolver;
    private XPathFunction bodyFunction;
    private XPathFunction headerFunction;
    private XPathFunction outBodyFunction;
    private XPathFunction outHeaderFunction;

    public XPathBuilder(String text) {
        this.text = text;
    }

    public static XPathBuilder xpath(String text) {
        return new XPathBuilder(text);
    }

    public static XPathBuilder xpath(String text, Class resultType) {
        XPathBuilder builder = new XPathBuilder(text);
        builder.setResultType(resultType);
        return builder;
    }

    @Override
    public String toString() {
        return "XPath: " + text;
    }

    public boolean matches(Exchange exchange) {
        Object booleanResult = evaluateAs(exchange, XPathConstants.BOOLEAN);
        return exchange.getContext().getTypeConverter().convertTo(Boolean.class, booleanResult);
    }

    public <T> T evaluate(Exchange exchange, Class<T> type) {
        Object result = evaluate(exchange);
        return exchange.getContext().getTypeConverter().convertTo(type, result);
    }

    // Builder methods
    // -------------------------------------------------------------------------

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder booleanResult() {
        resultQName = XPathConstants.BOOLEAN;
        return this;
    }

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder nodeResult() {
        resultQName = XPathConstants.NODE;
        return this;
    }

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder nodeSetResult() {
        resultQName = XPathConstants.NODESET;
        return this;
    }

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder numberResult() {
        resultQName = XPathConstants.NUMBER;
        return this;
    }

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder stringResult() {
        resultQName = XPathConstants.STRING;
        return this;
    }

    /**
     * Sets the expression result type to boolean
     *
     * @return the current builder
     */
    public XPathBuilder resultType(Class<?> resultType) {
        setResultType(resultType);
        return this;
    }

    /**
     * Sets the object model URI to use
     *
     * @return the current builder
     */
    public XPathBuilder objectModel(String uri) {
        this.objectModelUri = uri;
        return this;
    }

    /**
     * Sets the {@link XPathFunctionResolver} instance to use on these XPath
     * expressions
     *
     * @return the current builder
     */
    public XPathBuilder functionResolver(XPathFunctionResolver functionResolver) {
        this.functionResolver = functionResolver;
        return this;
    }

    /**
     * Registers the namespace prefix and URI with the builder so that the
     * prefix can be used in XPath expressions
     *
     * @param prefix is the namespace prefix that can be used in the XPath
     *                expressions
     * @param uri is the namespace URI to which the prefix refers
     * @return the current builder
     */
    public XPathBuilder namespace(String prefix, String uri) {
        getNamespaceContext().add(prefix, uri);
        return this;
    }

    /**
     * Registers namespaces with the builder so that the registered
     * prefixes can be used in XPath expressions
     *
     * @param namespaces is namespaces object that should be used in the
     *                      XPath expression
     * @return the current builder
     */
    public XPathBuilder namespaces(Namespaces namespaces) {
        namespaces.configure(this);
        return this;
    }

    /**
     * Registers a variable (in the global namespace) which can be referred to
     * from XPath expressions
     */
    public XPathBuilder variable(String name, Object value) {
        getVariableResolver().addVariable(name, value);
        return this;
    }

    /**
     * Configures the document type to use.
     * <p/>
     * The document type controls which kind of Class Camel should convert the payload
     * to before doing the xpath evaluation.
     * <p/>
     * For example you can set it to {@link InputSource} to use SAX streams.
     * By default Camel uses {@link Document} as the type.
     *
     * @param documentType the document type
     * @return the current builder
     */
    public XPathBuilder documentType(Class documentType) {
        setDocumentType(documentType);
        return this;
    }

    // Properties
    // -------------------------------------------------------------------------
    public XPathFactory getXPathFactory() throws XPathFactoryConfigurationException {
        if (xpathFactory == null) {
            if (objectModelUri != null) {
                xpathFactory = XPathFactory.newInstance(objectModelUri);
            }
            xpathFactory = XPathFactory.newInstance();
        }
        return xpathFactory;
    }

    public void setXPathFactory(XPathFactory xpathFactory) {
        this.xpathFactory = xpathFactory;
    }

    public Class getDocumentType() {
        return documentType;
    }

    public void setDocumentType(Class documentType) {
        this.documentType = documentType;
    }

    public String getText() {
        return text;
    }

    public QName getResultQName() {
        return resultQName;
    }

    public void setResultQName(QName resultQName) {
        this.resultQName = resultQName;
    }

    public DefaultNamespaceContext getNamespaceContext() {
        if (namespaceContext == null) {
            try {
                DefaultNamespaceContext defaultNamespaceContext = new DefaultNamespaceContext(getXPathFactory());
                populateDefaultNamespaces(defaultNamespaceContext);
                namespaceContext = defaultNamespaceContext;
            } catch (XPathFactoryConfigurationException e) {
                throw new RuntimeExpressionException(e);
            }
        }
        return namespaceContext;
    }

    public void setNamespaceContext(DefaultNamespaceContext namespaceContext) {
        this.namespaceContext = namespaceContext;
    }

    public XPathFunctionResolver getFunctionResolver() {
        return functionResolver;
    }

    public void setFunctionResolver(XPathFunctionResolver functionResolver) {
        this.functionResolver = functionResolver;
    }

    public void setNamespaces(Map<String, String> namespaces) {
        getNamespaceContext().setNamespaces(namespaces);
    }

    public XPathFunction getBodyFunction() {
        if (bodyFunction == null) {
            bodyFunction = new XPathFunction() {
                public Object evaluate(List list) throws XPathFunctionException {
                    if (exchange == null) {
                        return null;
                    }
                    return exchange.get().getIn().getBody();
                }
            };
        }
        return bodyFunction;
    }

    public void setBodyFunction(XPathFunction bodyFunction) {
        this.bodyFunction = bodyFunction;
    }

    public XPathFunction getHeaderFunction() {
        if (headerFunction == null) {
            headerFunction = new XPathFunction() {
                public Object evaluate(List list) throws XPathFunctionException {
                    if (exchange != null && !list.isEmpty()) {
                        Object value = list.get(0);
                        if (value != null) {
                            return exchange.get().getIn().getHeader(value.toString());
                        }
                    }
                    return null;
                }
            };
        }
        return headerFunction;
    }

    public void setHeaderFunction(XPathFunction headerFunction) {
        this.headerFunction = headerFunction;
    }

    public XPathFunction getOutBodyFunction() {
        if (outBodyFunction == null) {
            outBodyFunction = new XPathFunction() {
                public Object evaluate(List list) throws XPathFunctionException {
                    if (exchange.get() != null && exchange.get().hasOut()) {
                        return exchange.get().getOut().getBody();
                    }
                    return null;
                }
            };
        }
        return outBodyFunction;
    }

    public void setOutBodyFunction(XPathFunction outBodyFunction) {
        this.outBodyFunction = outBodyFunction;
    }

    public XPathFunction getOutHeaderFunction() {
        if (outHeaderFunction == null) {
            outHeaderFunction = new XPathFunction() {
                public Object evaluate(List list) throws XPathFunctionException {
                    if (exchange.get() != null && !list.isEmpty()) {
                        Object value = list.get(0);
                        if (value != null) {
                            return exchange.get().getOut().getHeader(value.toString());
                        }
                    }
                    return null;
                }
            };
        }
        return outHeaderFunction;
    }

    public void setOutHeaderFunction(XPathFunction outHeaderFunction) {
        this.outHeaderFunction = outHeaderFunction;
    }

    public Class getResultType() {
        return resultType;
    }

    public void setResultType(Class resultType) {
        this.resultType = resultType;
        if (Number.class.isAssignableFrom(resultType)) {
            numberResult();
        } else if (String.class.isAssignableFrom(resultType)) {
            stringResult();
        } else if (Boolean.class.isAssignableFrom(resultType)) {
            booleanResult();
        } else if (Node.class.isAssignableFrom(resultType)) {
            nodeResult();
        } else if (NodeList.class.isAssignableFrom(resultType)) {
            nodeSetResult();
        }
    }

    // Implementation methods
    // -------------------------------------------------------------------------

    protected Object evaluate(Exchange exchange) {
        Object answer = evaluateAs(exchange, resultQName);
        if (resultType != null) {
            return ExchangeHelper.convertToType(exchange, resultType, answer);
        }
        return answer;
    }

    /**
     * Evaluates the expression as the given result type
     */
    protected Object evaluateAs(Exchange exchange, QName resultQName) {
        // pool a pre compiled expression from pool
        XPathExpression xpathExpression = pool.poll();
        if (xpathExpression == null) {
            LOG.trace("Creating new XPathExpression as none was available from pool");
            // no avail in pool then create one
            try {
                xpathExpression = createXPathExpression();
            } catch (XPathExpressionException e) {
                throw new InvalidXPathExpression(getText(), e);
            } catch (Exception e) {
                throw new RuntimeExpressionException("Cannot create xpath expression", e);
            }
        } else {
            LOG.trace("Acquired XPathExpression from pool");
        }
        try {
            return doInEvaluateAs(xpathExpression, exchange, resultQName);
        } finally {
            // release it back to the pool
            pool.add(xpathExpression);
            LOG.trace("Released XPathExpression back to pool");
        }
    }

    protected Object doInEvaluateAs(XPathExpression xpathExpression, Exchange exchange, QName resultQName) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Evaluating exchange: " + exchange + " as: " + resultQName);
        }

        Object answer;

        // set exchange and variable resolver as thread locals for concurrency
        this.exchange.set(exchange);

        try {
            Object document = getDocument(exchange);
            if (resultQName != null) {
                if (document instanceof InputSource) {
                    InputSource inputSource = (InputSource) document;
                    answer = xpathExpression.evaluate(inputSource, resultQName);
                } else if (document instanceof DOMSource) {
                    DOMSource source = (DOMSource) document;
                    answer = xpathExpression.evaluate(source.getNode(), resultQName);
                } else {
                    answer = xpathExpression.evaluate(document, resultQName);
                }
            } else {
                if (document instanceof InputSource) {
                    InputSource inputSource = (InputSource) document;
                    answer = xpathExpression.evaluate(inputSource);
                } else if (document instanceof DOMSource) {
                    DOMSource source = (DOMSource) document;
                    answer = xpathExpression.evaluate(source.getNode());
                } else {
                    answer = xpathExpression.evaluate(document);
                }
            }
        } catch (XPathExpressionException e) {
            throw new InvalidXPathExpression(getText(), e);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Done evaluating exchange: " + exchange + " as: " + resultQName + " with result: " + answer);
        }
        return answer;
    }

    protected XPathExpression createXPathExpression() throws XPathExpressionException, XPathFactoryConfigurationException {
        XPath xPath = getXPathFactory().newXPath();

        xPath.setNamespaceContext(getNamespaceContext());
        xPath.setXPathVariableResolver(getVariableResolver());

        XPathFunctionResolver parentResolver = getFunctionResolver();
        if (parentResolver == null) {
            parentResolver = xPath.getXPathFunctionResolver();
        }
        xPath.setXPathFunctionResolver(createDefaultFunctionResolver(parentResolver));
        return xPath.compile(text);
    }

    /**
     * Lets populate a number of standard prefixes if they are not already there
     */
    protected void populateDefaultNamespaces(DefaultNamespaceContext context) {
        setNamespaceIfNotPresent(context, "in", IN_NAMESPACE);
        setNamespaceIfNotPresent(context, "out", OUT_NAMESPACE);
        setNamespaceIfNotPresent(context, "env", Namespaces.ENVIRONMENT_VARIABLES);
        setNamespaceIfNotPresent(context, "system", Namespaces.SYSTEM_PROPERTIES_NAMESPACE);
    }

    protected void setNamespaceIfNotPresent(DefaultNamespaceContext context, String prefix, String uri) {
        if (context != null) {
            String current = context.getNamespaceURI(prefix);
            if (current == null) {
                context.add(prefix, uri);
            }
        }
    }

    protected XPathFunctionResolver createDefaultFunctionResolver(final XPathFunctionResolver parent) {
        return new XPathFunctionResolver() {
            public XPathFunction resolveFunction(QName qName, int argumentCount) {
                XPathFunction answer = null;
                if (parent != null) {
                    answer = parent.resolveFunction(qName, argumentCount);
                }
                if (answer == null) {
                    if (isMatchingNamespaceOrEmptyNamespace(qName.getNamespaceURI(), IN_NAMESPACE)
                        || isMatchingNamespaceOrEmptyNamespace(qName.getNamespaceURI(), DEFAULT_NAMESPACE)) {
                        String localPart = qName.getLocalPart();
                        if (localPart.equals("body") && argumentCount == 0) {
                            return getBodyFunction();
                        }
                        if (localPart.equals("header") && argumentCount == 1) {
                            return getHeaderFunction();
                        }
                    }
                    if (isMatchingNamespaceOrEmptyNamespace(qName.getNamespaceURI(), OUT_NAMESPACE)) {
                        String localPart = qName.getLocalPart();
                        if (localPart.equals("body") && argumentCount == 0) {
                            return getOutBodyFunction();
                        }
                        if (localPart.equals("header") && argumentCount == 1) {
                            return getOutHeaderFunction();
                        }
                    }
                }
                return answer;
            }
        };
    }

    /**
     * Strategy method to extract the document from the exchange
     */
    @SuppressWarnings("unchecked")
    protected Object getDocument(Exchange exchange) {
        Message in = exchange.getIn();
        Class type = getDocumentType();
        Object answer = null;
        if (type != null) {
            answer = in.getBody(type);
        }

        if (answer == null) {
            answer = in.getBody();
        }

        // lets try coerce some common types into something JAXP can deal with
        if (answer instanceof GenericFile) {
            // special for files so we can work with them out of the box
            InputStream is = exchange.getContext().getTypeConverter().convertTo(InputStream.class, answer);
            answer = new InputSource(is);
        } else if (answer instanceof String) {
            answer = new InputSource(new StringReader(answer.toString()));
        }

        // call the reset if the in message body is StreamCache
        MessageHelper.resetStreamCache(exchange.getIn());
        return answer;
    }

    private MessageVariableResolver getVariableResolver() {
        MessageVariableResolver resolver = variableResolver.get();
        if (resolver == null) {
            resolver = new MessageVariableResolver(exchange);
            variableResolver.set(resolver);
        }
        return resolver;
    }

    public void start() throws Exception {
    }

    public void stop() throws Exception {
        pool.clear();
    }
}
