var expenseList = JSON.parse(localStorage.getItem('expenseItems')) || [  ];
var incomeList = JSON.parse(localStorage.getItem('incomeItems')) || [];

var printExpense = function() {
    
}




$('.add-expense').on('click', function() {
    var expenseName = $('#expenseName').val().trim();
    var expenseAmount = $('#expenseAmount').val().trim();

    var expenseNameText = $('<p>');
    var expenseAmountText = $('<p>');

    expenseNameText.text(expenseName);
    expenseAmountText.text(expenseAmount);

    $('.expenses-name').append(expenseNameText);
    $('.expenses-amount').append(expenseAmountText);

    var expenseItems = {
        name: expenseName,
        amount: expenseAmount
    }

    // expenseList.push(expenseItems)
    
    localStorage.setItem("expenseItems", JSON.stringify(expenseItems))

    $('#expenseName').val("")
    $('#expenseAmount').val("")

    printExpense(expenseList)
});

$('.add-income').on('click', function() {
    var incomeName = $('#incomeName').val().trim();
    var incomeAmount = $('#incomeAmount').val().trim();

    var incomeNameText = $('<p>');
    var incomeAmountText = $('<p>');

    incomeNameText.text(incomeName);
    incomeAmountText.text(incomeAmount);

    $('.incomes-name').append(incomeNameText);
    $('.incomes-amount').append(incomeAmountText);

    var incomeItems = {
        name: incomeName,
        amount: incomeAmount
    }
    
    localStorage.setItem("incomeItems", JSON.stringify(incomeItems))

    $('#incomeName').val("")
    $('#incomeAmount').val("")

    
});
