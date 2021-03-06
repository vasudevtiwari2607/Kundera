package com.impetus.client.crud.autogeneratedid;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "product", schema = "KunderaExamples@mongoTest")
public class Product implements Serializable
{
    private static final long serialVersionUID = 4242890624607140773L;

    /**
     * Id of the product
     */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "product_id")
    private String productId;

    /**
     * Name of the product
     */
    @Column(name = "product_name")
    private String productName;

    public String getProductId()
    {
        return productId;
    }

    public String getProductName()
    {
        return productName;
    }

    public void setProductName(String productName)
    {
        this.productName = productName;
    }
}