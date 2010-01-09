package com.kbs.trook;

public interface ILinkFixer
{
    // Here's a chance to mangle a link
    // before it is stuffed into the code.
    public String fix(String uri);
}
