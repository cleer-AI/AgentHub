# 个税计算脚本
# 参数说明：
# salary: 月度税前工资（必填）
# insurance: 月度五险一金个人缴纳部分（可选，默认0）
# special_deduction: 月度专项附加扣除（可选，默认0）
# other_deduction: 其他依法扣除项（可选，默认0）

# 接收参数
salary = float(args.get("salary", 0))
insurance = float(args.get("insurance", 0))
special_deduction = float(args.get("special_deduction", 0))
other_deduction = float(args.get("other_deduction", 0))

basic_deduction = 5000.0

# 计算应纳税所得额
taxable_income = salary - insurance - special_deduction - other_deduction - basic_deduction

# 计算税率和速算扣除数
if taxable_income <= 0:
    tax_rate = 0.0
    quick_deduction = 0.0
    tax = 0.0
else:
    if taxable_income <= 3000.0:
        tax_rate = 0.03
        quick_deduction = 0.0
    elif taxable_income <= 12000.0:
        tax_rate = 0.10
        quick_deduction = 210.0
    elif taxable_income <= 25000.0:
        tax_rate = 0.20
        quick_deduction = 1410.0
    elif taxable_income <= 35000.0:
        tax_rate = 0.25
        quick_deduction = 2660.0
    elif taxable_income <= 55000.0:
        tax_rate = 0.30
        quick_deduction = 4410.0
    elif taxable_income <= 80000.0:
        tax_rate = 0.35
        quick_deduction = 7160.0
    else:
        tax_rate = 0.45
        quick_deduction = 15160.0
    
    tax = taxable_income * tax_rate - quick_deduction

# 准备结果
result = {
    "taxable_income": round(taxable_income, 2),
    "tax_rate": tax_rate,
    "quick_deduction": quick_deduction,
    "tax": round(max(tax, 0.0), 2)
}

# 输出结果
result
