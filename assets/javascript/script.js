var saveExpenses = function (newDataObject) {
    var currentExpenses = JSON.parse(localStorage.getItem('expenseItems')) || [];
    currentExpenses.push(newDataObject);
    localStorage.setItem('expenseItems', JSON.stringify(currentExpenses));
}
var saveIncome = function (newDataObject2) {
    var currentIncome = JSON.parse(localStorage.getItem('incomeItems')) || [];
    currentIncome.push(newDataObject2);
    localStorage.setItem('incomeItems', JSON.stringify(currentIncome));
}

var getExpenseValHandler = function () {
    //get the values of the form
    var expenseName = $('#expenseName').val().trim();
    var expenseAmount = $('#expenseAmount').val().trim();

    var expenseItems = {
        name: expenseName,
        amount: expenseAmount
    }

    if (!expenseName) {
        alert("Please fill out all the feilds!")
        window.location.reload()
    } else {
        saveExpenses(expenseItems)
    }
    printExpenses()

    $('#expenseName').val("")
    $('#expenseAmount').val("")

    expenseTotal()
};

var getIncomeValHandler = function () {
    var incomeName = $('#incomeName').val().trim();
    var incomeAmount = $('#incomeAmount').val().trim();

    var incomeItems = {
        name: incomeName,
        amount: incomeAmount
    };

    if (!incomeName) {
        alert("Please fill out all fields!")
        window.location.reload()
    } else {
        saveIncome(incomeItems)
    }

    printIncomes()

    $('#incomeName').val("")
    $('#incomeAmount').val("")

    incomeTotal();
}
var printExpenses = function () {
    var currentExpenses = JSON.parse(localStorage.getItem('expenseItems')) || []

    $('.expenses-amount').empty()
    currentExpenses.forEach(element => {
        // create p tags
        var expenseNameText = $('<p>');
        var expenseAmountText = $('<p>');

        expenseNameText.addClass("expenseNameP");
        expenseAmountText.addClass("expenseAmountP");

        expenseNameText.text(element.name)
        expenseAmountText.text(element.amount)

        $('.expenses-name').append(expenseNameText);
        $('.expenses-amount').append(expenseAmountText);
    });
}

printExpenses()

$(".total-expense").find("span").text("0")

var printIncomes = function() {
    var currentIncome = JSON.parse(localStorage.getItem('incomeItems')) || [];

    $('.income-amount').empty();
    currentIncome.forEach(element => {
        var incomeNameText = $('<p>');
        var incomeAmountText = $('<p>');

        incomeNameText.addClass("incomeNameP");
        incomeAmountText.addClass("incomeAmountP");

        incomeNameText.text(element.name);
        incomeAmountText.text(element.amount);

        $('.incomes-name').append(incomeNameText);
        $('.incomes-amount').append(incomeAmountText);
    });
};
printIncomes();
$(".total-income").find("span").text("0")

var leftOver = function() {
    var totalAmount = incomeCounter - expenseCounter;
    console.log(totalAmount)

    $(".left-over").find("span").text(totalAmount)
}

var expenseCounter = 0;
var expenseTotal = function () {
    
    var expenseAmountArrObj = JSON.parse(localStorage.getItem('expenseItems')) || [];
    for (var i = 0; i < expenseAmountArrObj.length; i++) {
        expenseCounter = parseInt(expenseAmountArrObj[i].amount) + expenseCounter;
    }
    $(".total-expense").find("span").text(expenseCounter)

}
expenseTotal()
var incomeCounter = 0;
var incomeTotal = function () {
    
    var incomeAmountArrObj = JSON.parse(localStorage.getItem('incomeItems')) || [];
    for (var i = 0; i < incomeAmountArrObj.length; i++) {
        incomeCounter = parseInt(incomeAmountArrObj[i].amount) + incomeCounter;
    }
    $('.total-income').find('span').text(incomeCounter);
}
incomeTotal();
leftOver()



$('.add-expense').on('click', getExpenseValHandler)
$('.add-income').on('click', getIncomeValHandler)
