package com.uav.lowaltitude.modules.alarm.api;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.context.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.platform.api.ApiException;

/** Additive action envelope. Existing strict DTO parsers still reject every other unknown field. */
@ControllerAdvice(basePackages={"com.uav.lowaltitude.modules.alarm.api","com.uav.lowaltitude.modules.disposal.api"})
public class UavRiskCaseRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper json;
    public UavRiskCaseRequestAdvice(ObjectMapper json){this.json=json;}
    @Override public boolean supports(MethodParameter p,Type type,Class<? extends HttpMessageConverter<?>> converter){
        return p.getParameterType()==String.class&&p.getContainingClass()!=UavRiskCaseController.class;
    }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter p,Type type,Class<? extends HttpMessageConverter<?>> converter)throws IOException{
        byte[] bytes=input.getBody().readAllBytes();byte[] output=bytes;
        try(var parser=json.getFactory().createParser(bytes)){
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var value=json.readTree(parser);
            if(value instanceof ObjectNode n&&(n.has("risk_case_id")||n.has("expected_case_version"))){
                if(parser.nextToken()!=null||!n.path("risk_case_id").isTextual()||n.path("risk_case_id").asText().isBlank()||n.path("risk_case_id").asText().length()>36
                        ||!n.path("expected_case_version").isIntegralNumber()||!n.path("expected_case_version").canConvertToLong()||n.path("expected_case_version").longValue()<0)throw invalid();
                if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes r){
                    r.getRequest().setAttribute(UavRiskCaseIdentity.REQUEST_CASE,n.path("risk_case_id").asText());
                    // A stage update must never prevent stopping/cancelling an existing device action.
                    boolean stopping=p.getContainingClass()==com.uav.lowaltitude.modules.disposal.api.EmergencyStopController.class
                            ||(p.getContainingClass()==com.uav.lowaltitude.modules.disposal.api.DisposalController.class
                            && java.util.Set.of("stop","cancel","reject").contains(p.getMethod().getName()));
                    if(!stopping)r.getRequest().setAttribute(UavRiskCaseIdentity.REQUEST_VERSION,n.path("expected_case_version").longValue());
                }
                n.remove("risk_case_id");n.remove("expected_case_version");output=json.writeValueAsBytes(n);
            }
        }catch(ApiException ex){throw ex;}catch(Exception invalid){/* Original strict parser reports malformed JSON. */}
        byte[] body=output;
        return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(body);}public HttpHeaders getHeaders(){HttpHeaders headers=new HttpHeaders();headers.putAll(input.getHeaders());headers.setContentLength(body.length);return headers;}};
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RISK_CONTEXT","必须提供有效风险编号和版本");}
}
