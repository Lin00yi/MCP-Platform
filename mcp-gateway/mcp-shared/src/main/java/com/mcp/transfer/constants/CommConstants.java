package com.mcp.transfer.constants;

/**
 * @Title: CommConstants
 * @Package: com.mcp.transfer.constants
 * @Description: 常量类
 * @author: zhangXu
 * @date: 2025/12/17 15:40
 * @version: V1.0
 */
public class CommConstants {

    public final static Integer DEFAULT_PAGE_SIZE = 20;

    public final static Integer DEFAULT_PAGE_NUMBER = 1;

    /**
     * 租户启用状态
     */
    public final static Integer TENANT_API_OPEN_STATUS = 1;



//------------------------政策查询方法

    /**
     * 政策地区查询
     */
    //根据地区查询政策id列表
    public final static String POLICE_METHOD_SELECT_POLICY_REGION_BY_REGIN = "select-policy-region-by-regin";

    //根据政策id查询地区
    public final static String POLICE_METHOD_SELECT_POLICY_REGION_BY_POLICY_ID = "select-policy-region-by-policy-id";


    /**
     * 政策行业查询
     */
    //根据行业查询政策id列表
    public final static String POLICE_METHOD_SELECT_POLICY_INDUSTRY_BY_INDUSTRY = "select-policy-industry-by-industry";

    //根据政策id查询行业
    public final static String POLICE_METHOD_SELECT_POLICY_INDUSTRY_BY_POLICY_ID = "select-policy-industry-by-policy-id";

    /**
     * 根据地区、行业、发布单位级别查询政策id列表
     */
    public final static String POLICE_METHOD_SELECT_POLICY_REGION_BY_REGIN_AND_INDUSTRY_AND_LEVEL = "select-policy-region-by-regin-and-industry-and-level";

    //根据地区和行业查询政策id列表
    public final static String POLICE_METHOD_SELECT_POLICY_REGION_BY_REGIN_AND_INDUSTRY = "select-policy-region-by-regin-and-industry";


    /**
     * 发布机构信息查询
     */
    //根据机构信息查询机构编码
    public final static String POLICE_METHOD_SELECT_POLICY_DEPT_BY_DEPT = "select-policy-dept-by-dept";

    /**
     * 政策信息查询
     */
    //根据政策id集合查询信息
    public final static String POLICE_METHOD_SELECT_POLICY_BASE_BY_POLICY_ID = "select-policy-base-by-policy-id";
    //根据政策标题查询政策信息
    public final static String POLICE_METHOD_SELECT_POLICY_BASE_BY_TITLE_OR_DEPT = "select-policy-base-by-title-or-dept";

    //根据政策id查询政策内容
    public final static String POLICE_METHOD_SELECT_POLICY_BODY_BY_POLICY_ID = "select-policy-body-by-policy-id";


    /**
     * 政策分析结果查询
     */
    public final static String POLICE_METHOD_SELECT_POLICY_ANALYSIS_RESULT_BY_POLICY_ID = "select-policy-analysis-result-by-policy-id";


//------------------------政策查询方法

    public final static String STRING = "string";
    public final static String INTEGER =  "integer";
    public final static String LONG =  "Long";
    public final static String LIST =  "list";
    public final static String IDS = "ids";
    public final static String INDUSTRY_NAME =  "industry_name";
    public final static String POLICY_ID = "policy_id";
    public final static String REGION_NAME =   "region_name";
    public final static String POLICY_TITLE =   "policy_title";
    public final static String POLICY_CONTENT =   "policy_content";
    public final static String DEPT_NAME =   "dept_name";
    public final static String DEPT_LEVEL_LIST =  "dept_level_list";
    public final static String KEY_WORD =  "key_word";


//------------------------政策字段常量


}
