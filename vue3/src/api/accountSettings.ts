import request from '@/utils/request'


//修改密码
export function getEditPsdApi(data){
    return request({
        url: '/ticketflow/user/user/update/password',
        method: 'post',
        data:data
    })
}
// 修改邮箱
export function getEditEmailApi(data){
    return request({
        url: '/ticketflow/user/user/update/email',
        method: 'post',
        data:data
    })
}
//修改手机号
export function getEditMobileApi(data){
    return request({
        url: '/ticketflow/user/user/update/mobile',
        method: 'post',
        data:data
    })
}
//实名认证
export function getAuthenticationApi(data){
    return request({
        url: '/ticketflow/user/user/authentication',
        method: 'post',
        data:data
    })
}
