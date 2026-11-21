package com.veloce.integration;

import com.veloce.common.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/google-calendar")
public class GoogleCalendarApi {
    private final GoogleCalendarService service;private final Limits limits;
    GoogleCalendarApi(GoogleCalendarService service,Limits limits){this.service=service;this.limits=limits;}
    @GetMapping Map<String,Object> status(@AuthenticationPrincipal UUID owner){return service.status(owner);}
    @PostMapping("/connect") Map<String,String> connect(@AuthenticationPrincipal UUID owner){limits.check("google-connect:"+owner,5,600);return service.authorize(owner);}
    @PostMapping("/sync") Map<String,Object> sync(@AuthenticationPrincipal UUID owner){limits.check("google-sync:"+owner,10,60);service.requestSync(owner);return service.status(owner);}
    @DeleteMapping void disconnect(@AuthenticationPrincipal UUID owner){service.disconnect(owner);}
    @GetMapping(value="/callback",produces=MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> callback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error) {
        boolean success=false;String message;
        try{service.callback(state,code,error);success=true;message="Google Calendar connected. Your active workspace schedules will sync automatically. Return to Veloce and check connection status.";}
        catch(Problem e){message=e.getMessage();}
        return ResponseEntity.status(success?200:400).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
            .body("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>Veloce Calendar</title></head>"
                +"<body style='font:18px system-ui;max-width:36rem;margin:15vh auto;padding:24px'><h1>"+(success?"Calendar connected":"Connection not completed")
                +"</h1><p>"+message.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</p></body></html>");
    }
}
