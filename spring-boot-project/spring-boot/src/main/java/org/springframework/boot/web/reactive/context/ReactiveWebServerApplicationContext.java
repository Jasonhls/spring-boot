/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.reactive.context;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.metrics.StartupStep;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.util.StringUtils;

/**
 * A {@link GenericReactiveWebApplicationContext} that can be used to bootstrap itself
 * from a contained {@link ReactiveWebServerFactory} bean.
 *
 * @author Brian Clozel
 * @since 2.0.0
 */
public class ReactiveWebServerApplicationContext extends GenericReactiveWebApplicationContext
		implements ConfigurableWebServerApplicationContext {

	private volatile WebServerManager serverManager;

	private String serverNamespace;

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext}.
	 */
	public ReactiveWebServerApplicationContext() {
	}

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext} with the given
	 * {@code DefaultListableBeanFactory}.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	public ReactiveWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
	}

	@Override
	public final void refresh() throws BeansException, IllegalStateException {
		try {
			super.refresh();
		}
		catch (RuntimeException ex) {
			WebServerManager serverManager = this.serverManager;
			if (serverManager != null) {
				serverManager.getWebServer().stop();
			}
			throw ex;
		}
	}

	@Override
	protected void onRefresh() {
		super.onRefresh();
		try {
			/**
			 * 创建ReactiveWebServer，即创建Reactive web容器并启动
			 */
			createWebServer();
		}
		catch (Throwable ex) {
			throw new ApplicationContextException("Unable to start reactive web server", ex);
		}
	}

	private void createWebServer() {
		WebServerManager serverManager = this.serverManager;
		if (serverManager == null) {
			StartupStep createWebServer = this.getApplicationStartup().start("spring.boot.webserver.create");
			String webServerFactoryBeanName = getWebServerFactoryBeanName();
			/**
			 * getWebServerFactory() 默认返回为NettyReactiveWebServerFactory，原因是因为spring-boot-starter-webflux包默认包含spring-boot-starter-reactor-netty包，
			 * 该包又包含了reactor-netty-http，因此会加载HttpServer.class这个class，然后由于spring-boot-autoconfigure项目会自动解析配置类ReactiveWebServerFactoryAutoConfiguration，
			 * 该配置又import了配置类ReactiveWebServerFactoryConfiguration，在ReactiveWebServerFactoryConfiguration配置类中因为有HttpServer.class，因此会默认使用Netty容器。
			 *
			 * 比如网关项目，用到了spring-cloud-starter-gateway包，该包本身就包含了spring-boot-starter-webflux包，因此默认也是使用Netty容器。
			 */
			ReactiveWebServerFactory webServerFactory = getWebServerFactory(webServerFactoryBeanName);
			createWebServer.tag("factory", webServerFactory.getClass().toString());
			boolean lazyInit = getBeanFactory().getBeanDefinition(webServerFactoryBeanName).isLazyInit();
			/**
			 * 创建ServerManager实例对象，this::getHttpHandler得到一个Supplier<HttpHandler>作为入参，this.getHttpHandler()方法现在不会调用，只有
			 * 在调用到supplier.get()方法的时候才会去调用this.getHttpHandler()方法，在WebServerManager#start()方法中this.handler.initializeHandler()调用到
			 *参考jdk8中Supplier<?>用法
			 */
			this.serverManager = new WebServerManager(this, webServerFactory, this::getHttpHandler, lazyInit);
			//注册关闭容器的LifeCycle
			getBeanFactory().registerSingleton("webServerGracefulShutdown",
					new WebServerGracefulShutdownLifecycle(this.serverManager.getWebServer()));
			//注册容器启动的LifeCycle
			/**
			 * 通过执行WebServerStartStopLifecycle的start方法来启动reactive web容器
			 */
			getBeanFactory().registerSingleton("webServerStartStop",
					new WebServerStartStopLifecycle(this.serverManager));
			createWebServer.end();
		}
		initPropertySources();
	}

	protected String getWebServerFactoryBeanName() {
		// Use bean names so that we don't consider the hierarchy
		String[] beanNames = getBeanFactory().getBeanNamesForType(ReactiveWebServerFactory.class);
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing ReactiveWebServerFactory bean.");
		}
		if (beanNames.length > 1) {
			throw new ApplicationContextException("Unable to start ReactiveWebApplicationContext due to multiple "
					+ "ReactiveWebServerFactory beans : " + StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		return beanNames[0];
	}

	protected ReactiveWebServerFactory getWebServerFactory(String factoryBeanName) {
		return getBeanFactory().getBean(factoryBeanName, ReactiveWebServerFactory.class);
	}

	/**
	 * Return the {@link HttpHandler} that should be used to process the reactive web
	 * server. By default this method searches for a suitable bean in the context itself.
	 * @return a {@link HttpHandler} (never {@code null}
	 */
	protected HttpHandler getHttpHandler() {
		// Use bean names so that we don't consider the hierarchy
		/**
		 * springboot模块spring-boot-autoconfigure项目META-INF目录下的spring.factories中配置有HttpHandlerAutoConfiguration，
		 * 会自动解析配置类HttpHandlerAutoConfiguration，会注入HttpHandler的bean定义，注入的bean对应的class类型为HttpWebHandlerAdapter
		 */
		String[] beanNames = getBeanFactory().getBeanNamesForType(HttpHandler.class);
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing HttpHandler bean.");
		}
		if (beanNames.length > 1) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to multiple HttpHandler beans : "
							+ StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		return getBeanFactory().getBean(beanNames[0], HttpHandler.class);
	}

	@Override
	protected void doClose() {
		if (isActive()) {
			AvailabilityChangeEvent.publish(this, ReadinessState.REFUSING_TRAFFIC);
		}
		super.doClose();
	}

	/**
	 * Returns the {@link WebServer} that was created by the context or {@code null} if
	 * the server has not yet been created.
	 * @return the web server
	 */
	@Override
	public WebServer getWebServer() {
		WebServerManager serverManager = this.serverManager;
		return (serverManager != null) ? serverManager.getWebServer() : null;
	}

	@Override
	public String getServerNamespace() {
		return this.serverNamespace;
	}

	@Override
	public void setServerNamespace(String serverNamespace) {
		this.serverNamespace = serverNamespace;
	}

}
