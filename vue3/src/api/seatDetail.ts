import request from '@/utils/request'

export function getSeatList(data) {
    return request({
        url: '/ticketflow/program/seat/relate/info',
        method: 'post',
        data:data

    })
}

