import request from '@/utils/request'

export function getProgramDetails(data) {
    return request({
        url: '/ticketflow/program/program/detail',
        method: 'post',
        data:data

    })
}

