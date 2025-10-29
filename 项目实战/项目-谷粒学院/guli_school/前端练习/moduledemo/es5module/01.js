// 定义成员：
const sum = function (a, b) {
    return parseInt(a) + parseInt(b)
}
const subtract = function (a, b) {
    return parseInt(a) - parseInt(b)
}
const multiply = function (a, b) {
    return parseInt(a) * parseInt(b)
}
const divide = function (a, b) {
    return parseInt(a) / parseInt(b)
}


//2 设置哪些方法可以被其他js调用
// 导出成员：
module.exports = {
    sum: sum,
    subtract: subtract,
    multiply: multiply,
    divide: divide
}