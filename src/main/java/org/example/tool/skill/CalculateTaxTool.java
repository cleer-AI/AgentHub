package org.example.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class CalculateTaxTool {

    @Tool(name = "calculate_individual_income_tax", description = "根据月度税前工资、五险一金和专项附加扣除计算个人所得税")
    public String calculateTax(
            @ToolParam(description = "月度税前工资，如20000") double salary,
            @ToolParam(description = "月度五险一金个人缴纳部分，可选，默认0") Double insurance,
            @ToolParam(description = "月度专项附加扣除，可选，默认0") Double specialDeduction,
            @ToolParam(description = "其他依法扣除项，可选，默认0") Double otherDeduction
    ) {
        double finalInsurance = (insurance != null) ? insurance : 0.0;
        double finalSpecialDeduction = (specialDeduction != null) ? specialDeduction : 0.0;
        double finalOtherDeduction = (otherDeduction != null) ? otherDeduction : 0.0;

        double basicDeduction = 5000.0;
        double taxableIncome = salary - finalInsurance - finalSpecialDeduction - finalOtherDeduction - basicDeduction;

        double taxRate = 0.0;
        double quickDeduction = 0.0;
        double tax = 0.0;

        if (taxableIncome > 0) {
            if (taxableIncome <= 3000.0) {
                taxRate = 0.03;
                quickDeduction = 0.0;
            } else if (taxableIncome <= 12000.0) {
                taxRate = 0.10;
                quickDeduction = 210.0;
            } else if (taxableIncome <= 25000.0) {
                taxRate = 0.20;
                quickDeduction = 1410.0;
            } else if (taxableIncome <= 35000.0) {
                taxRate = 0.25;
                quickDeduction = 2660.0;
            } else if (taxableIncome <= 55000.0) {
                taxRate = 0.30;
                quickDeduction = 4410.0;
            } else if (taxableIncome <= 80000.0) {
                taxRate = 0.35;
                quickDeduction = 7160.0;
            } else {
                taxRate = 0.45;
                quickDeduction = 15160.0;
            }

            tax = taxableIncome * taxRate - quickDeduction;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taxable_income", Math.round(taxableIncome * 100.0) / 100.0);
        result.put("tax_rate", taxRate);
        result.put("quick_deduction", quickDeduction);
        result.put("tax", Math.max(Math.round(tax * 100.0) / 100.0, 0.0));

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
