var saveData = function (newDataObject) {
    var currentData = JSON.parse(localStorage.getItem('expenseItems')) || [];
    currentData.push(newDataObject);
    localStorage.setItem('expenseItems', JSON.stringify(currentData))
}

var getExpenseValHandler = function () {
    //get the values of the form
    var expenseName = $('#expenseName').val().trim();
    var expenseAmount = $('#expenseAmount').val().trim();

    var expenseItems = {
        name: expenseName,
        amount: expenseAmount
    }
    console.log(expenseItems)
    if (!expenseName) {
        alert("Please fill out all the feilds!")
        window.location.reload()
    } else {
        saveData(expenseItems)
    }
    printExpenses()

    $('#expenseName').val("")
    $('#expenseAmount').val("")

    expenseTotal()
};

var printExpenses = function () {
    var currentData = JSON.parse(localStorage.getItem('expenseItems')) || []

    $('.expenses-amount').empty()
        currentData.forEach(element => {
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

var expenseTotal = function () {
    var expenseCounter = 0;
    var expenseAmountArrObj = JSON.parse(localStorage.getItem('expenseItems')) || [];
    for (var i = 0; i < expenseAmountArrObj.length; i++) {
        expenseCounter = parseInt(expenseAmountArrObj[i].amount) + expenseCounter;
        console.log(parseInt(expenseAmountArrObj[i].amount))
    }
    $(".total-expense").find("span").text(expenseCounter)

}
expenseTotal()

$('.add-income').on('click', function () {
    var incomeName = $('#incomeName').val().trim();
    var incomeAmount = $('#incomeAmount').val().trim();

    var incomeNameText = $('<p>');
    var incomeAmountText = $('<p>');

    incomeNameText.text(incomeName);
    incomeAmountText.text(incomeAmount);

    $('.incomes-name').append(incomeNameText);
    $('.incomes-amount').append(incomeAmountText);

    var incomeAmountNumber = parseInt(incomeAmount);

    incomeAmountNumberArr.push(incomeAmountNumber);

    var incomeItems = {
        name: incomeName,
        amount: incomeAmount
    }

    $('#incomeName').val("")
    $('#incomeAmount').val("")

    incomeTotal()
});


var incomeTotal = function () {
    var incomeCounter = 0;
    for (var i = 0; i < incomeAmountNumberArr.length; i++) {
        incomeCounter = incomeAmountNumberArr[i] + incomeCounter;
    }
    $('.total-income').find('span').text(incomeCounter);
}

$('.add-expense').on('click', getExpenseValHandler)
