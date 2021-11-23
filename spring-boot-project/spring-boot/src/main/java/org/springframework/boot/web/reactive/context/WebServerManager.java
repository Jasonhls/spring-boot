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

import java.util.function.Supplier;

import reactor.core.publisher.Mono;

import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;

/**
 * Internal class used to manage the server and the {@link HttpHandler}, taking care not
 * to initialize the handler too early.
 *
 * @author Andy Wilkinson
 */
class WebServerManager {

	private final ReactiveWebServerApplicationContext applicationContext;

	//上层方法处理者的抽象
	private final DelayedInitializationHttpHandler handler;

	//底层服务器的抽象
	private final WebServer webServer;

	WebServerManager(ReactiveWebServerApplicationContext applicationContext, ReactiveWebServerFactory factory,
			Supplier<HttpHandler> handlerSupplier, boolean lazyInit) {
		this.applicationContext = applicationContext;
		Assert.notNull(factory, "Factory must not be null");
		this.handler = new DelayedInitializationHttpHandler(handlerSupplier, lazyInit);
		/**
		 * 默认的factory为NettyReactiveWebServerFactory
		 */
		this.webServer = factory.getWebServer(this.handler);
	}

	void start() {
		//实例化HttpHandler，通过调用ReactiveWebServerApplicationContext.getHttpHandler方法，该方法通过调用getBean方法实例化WebServerManager对象的属性HttpHandler
		//上述实例化得到的对象为HttpWebHandlerAdapter(封装了通过@EanbleWebflux注入的DispatcherHandler对象)，把该对象封装到DelayedInitializationHttpHandler对象的delegate属性中。
		this.handler.initializeHandler();
		/**
		 * 启动WebServer容器，默认为NettyWebServer
		 */
		this.webServer.start();
		this.applicationContext
				.publishEvent(new ReactiveWebServerInitializedEvent(this.webServer, this.applicationContext));
	}

	void shutDownGracefully(GracefulShutdownCallback callback) {
		this.webServer.shutDownGracefully(callback);
	}

	void stop() {
		this.webServer.stop();
	}

	WebServer getWebServer() {
		return this.webServer;
	}

	HttpHandler getHandler() {
		return this.handler;
	}

	/**
	 * A delayed {@link HttpHandler} that doesn't initialize things too early.
	 */
	static final class DelayedInitializationHttpHandler implements HttpHandler {

		private final Supplier<HttpHandler> handlerSupplier;

		private final boolean lazyInit;

		//生成的delegate是HttpWebHandlerAdapter对象，它包装ExceptionHandlingWebHandler实例（
		// 它又包装了FilteringWebHandler对象，FilteringWebHandler对象又封装DispatcherHandler实例）
		private volatile HttpHandler delegate = this::handleUninitialized;

		private DelayedInitializationHttpHandler(Supplier<HttpHandler> handlerSupplier, boolean lazyInit) {
			this.handlerSupplier = handlerSupplier;
			this.lazyInit = lazyInit;
		}

		private Mono<Void> handleUninitialized(ServerHttpRequest request, ServerHttpResponse response) {
			throw new IllegalStateException("The HttpHandler has not yet been initialized");
		}

		/**
		 * 处理响应式http请求的核心方法
		 * @param request
		 * @param response
		 * @return
		 */
		@Override
		public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
			/**
			 * 处理响应式http请求的核心方法
			 * this.delegate为Spring中的HttpWebHandlerAdapter对象
			 */
			return this.delegate.handle(request, response);
		}

		/**
		 * NettyWebServer容器启动的时候会调用这个方法，响应式的上下文对象为ReactiveWebServerApplicationContext，
		 * 通过调用它的属性serverManager(属于WebServerManager对象)的start方法启动reactive web容器
		 */
		void initializeHandler() {
			this.delegate = this.lazyInit ? new LazyHttpHandler(this.handlerSupplier) : this.handlerSupplier.get();
		}

		HttpHandler getHandler() {
			return this.delegate;
		}

	}

	/**
	 * {@link HttpHandler} that initializes its delegate on first request.
	 */
	private static final class LazyHttpHandler implements HttpHandler {

		private final Mono<HttpHandler> delegate;

		private LazyHttpHandler(Supplier<HttpHandler> handlerSupplier) {
			this.delegate = Mono.fromSupplier(handlerSupplier);
		}

		@Override
		public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
			return this.delegate.flatMap((handler) -> handler.handle(request, response));
		}

	}

}
