package pres.lnk.jxlss.demo;/**
 * @Author lnk
 * @Date 2018/1/23
 */

import java.util.Date;

/**
 * 员工信息
 * @Author lnk
 * @Date 2018/1/23
 */
public class Employee {
    private String name = "张三";
    private Integer gender = 1;
    private String nation = "汉族";
    private String nativePlace = "广东";
    private Date birthDate = new Date();
    private String address = "唐宁街10号";
    private String education = "本科";
    private String phone = "13111222333";
    private String picture = "zhangsan.jpg";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public String getNation() {
        return nation;
    }

    public void setNation(String nation) {
        this.nation = nation;
    }

    public String getNativePlace() {
        return nativePlace;
    }

    public void setNativePlace(String nativePlace) {
        this.nativePlace = nativePlace;
    }

    public Date getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(Date birthDate) {
        this.birthDate = birthDate;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }
}
