package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Api调用数据 dto
 */
@Data
@Schema(title="ApiDataDto", description ="api被限制调用记录")
public class AddApiDataDto {
    
    @Schema(name ="headVersion", type ="String", description ="headVersion")
    private String headVersion;
    
    @Schema(name ="apiAddress", type ="String", description ="apiAddress")
    private String apiAddress;
    
    @Schema(name ="apiMethod", type ="String", description ="apiMethod")
    private String apiMethod;
    
    @Schema(name ="apiBody", type ="String", description ="apiBody")
    private String apiBody;
    
    @Schema(name ="apiParams", type ="String", description ="apiParams")
    private String apiParams;
    
    @Schema(name ="apiUrl", type ="String", description ="apiUrl")
    private String apiUrl;
    
    @Schema(name ="callDayTime", type ="String", description ="callDayTime")
    private String callDayTime;
    
    @Schema(name ="callHourTime", type ="String", description ="callHourTime")
    private String callHourTime;
    
    @Schema(name ="callMinuteTime", type ="String", description ="callMinuteTime")
    private String callMinuteTime;
    
    @Schema(name ="callSecondTime", type ="String", description ="callSecondTime")
    private String callSecondTime;
    
    @Schema(name ="type", type ="Integer", description ="type")
    private Integer type;
}
