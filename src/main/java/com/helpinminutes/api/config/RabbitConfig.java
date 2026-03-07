package com.helpinminutes.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_PHOTOS = "photos.exchange";
    public static final String QUEUE_PHOTO_UPLOADED = "photo.uploaded.queue";
    public static final String ROUTING_KEY_PHOTO_UPLOADED = "photo.uploaded";

    public static final String QUEUE_PHOTO_DLQ = "photo.dlq";
    public static final String ROUTING_KEY_PHOTO_DLQ = "photo.dlq";

    @Bean
    public DirectExchange photosExchange() {
        return new DirectExchange(EXCHANGE_PHOTOS);
    }

    @Bean
    public Queue photoUploadedQueue() {
        return QueueBuilder.durable(QUEUE_PHOTO_UPLOADED)
                .withArgument("x-dead-letter-exchange", EXCHANGE_PHOTOS)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_PHOTO_DLQ)
                .build();
    }

    @Bean
    public Queue photoDlq() {
        return QueueBuilder.durable(QUEUE_PHOTO_DLQ).build();
    }

    @Bean
    public Binding photoUploadedBinding(Queue photoUploadedQueue, DirectExchange photosExchange) {
        return BindingBuilder.bind(photoUploadedQueue).to(photosExchange).with(ROUTING_KEY_PHOTO_UPLOADED);
    }

    @Bean
    public Binding photoDlqBinding(Queue photoDlq, DirectExchange photosExchange) {
        return BindingBuilder.bind(photoDlq).to(photosExchange).with(ROUTING_KEY_PHOTO_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }
}
