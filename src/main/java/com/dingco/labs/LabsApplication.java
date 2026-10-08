package com.dingco.labs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class LabsApplication {

    public static void main(String[] args) {
        String lab = Arrays.stream(args)
                .filter(a -> a.startsWith("--lab="))
                .map(a -> a.substring("--lab=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("--lab=w3|w4|w5|w6 를 지정하세요"));

        SpringApplication app = new SpringApplication(LabsApplication.class);
        app.setAdditionalProfiles(lab);
        // w4만 HTTP 서버로 띄우고, 나머지는 실험을 끝내면 종료한다.
        if (!lab.equals("w4")) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        var context = app.run(args);
        if (!lab.equals("w4")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
