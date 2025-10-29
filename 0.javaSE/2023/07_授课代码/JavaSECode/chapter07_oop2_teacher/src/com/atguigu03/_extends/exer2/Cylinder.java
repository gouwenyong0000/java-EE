package com.atguigu03._extends.exer2;

/**
 * ClassName: Cylinder
 * Description:
 *          圆柱类
 * @Author 尚硅谷-宋红康
 * @Create 10:01
 * @Version 1.0
 */
public class Cylinder extends Circle{

    private double length;//高

    public Cylinder(){
        length = 1;
    }

    public double getLength() {
        return length;
    }

    public void setLength(double length) {
        this.length = length;
    }

    //求圆柱的体积
    public double findVolume(){
//        return 3.14 * getRadius() * getRadius() * getLength();

        return findArea() * getLength();
    }
}
