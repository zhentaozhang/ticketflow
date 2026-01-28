import request from '@/utils/request'
 /**
 * 登录接口
 * @param {Object} params
 * @param {string} params.email    用户邮箱(手机号任选其一)
 * @param {string} params.mobile   用户手机号(邮箱任选其一)
 * @param {string} params.password 密码
 * @param {string} params.code     渠道code 0001(pc网站)
 * @returns {*}
 */
export function loginApi({ email, mobile, password, code }) {
    return request({
        url: '/ticketflow/user/user/login',
        method: 'post',
        data: { email, mobile, password, code }
    })
}

 /**
  * 退出接口
  * @param {Object} params
  * @param {string} params.code  渠道code
  * @param {string} params.token 用户token
  * @returns {*}
  */
 export function logoutApi({ code, token }) {
     return request({
         url: '/ticketflow/user/user/logout',
         method: 'post',
         data: { code, token }
     })
 }

 /**
  * 检查是否需要验证码
  * @returns {*}
  */
 export function isCaptchaApi(){
     return request({
         url: '/ticketflow/user/user/captcha/check/need',
         method: 'post'
     })
 }



 export function registerApi(data){
     return request({
         url: '/ticketflow/user/user/register',
         method: 'post',
         data:data
     })
 }
