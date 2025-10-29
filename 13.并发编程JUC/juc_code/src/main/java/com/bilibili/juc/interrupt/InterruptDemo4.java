package com.bilibili.juc.interrupt;

import java.util.concurrent.locks.LockSupport;

/**
 * @auther zzyy
 * @create 2022-01-20 11:58
 */
public class InterruptDemo4
{
    public static void main(String[] args)
    {
        //测试当前线程是否被中断（检查中断标志），返回一个boolean并清除中断状态，
        // 第二次再调用时中断状态已经被清除，将返回一个false。


        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println("----1");
        Thread.currentThread().interrupt();// 中断标志位设置为true
        System.out.println("----2");
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());

        LockSupport.park();

        Thread.interrupted();//静态方法

        Thread.currentThread().isInterrupted();//实例方法
    }
}
