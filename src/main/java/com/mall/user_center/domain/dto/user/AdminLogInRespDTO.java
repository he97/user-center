package com.mall.user_center.domain.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AdminLogInRespDTO {

    private JwtTokenRespDTO jwtTokenResp;

    private UserRespDTO user;

    private String status;

    private String message;
}
