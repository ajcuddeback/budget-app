var expenseAmountNumberArr = [];
var incomeAmountNumberArr = [];


$('.add-expense').on('click', function() {
    //get the values of the form
    var expenseName = $('#expenseName').val().trim();
    var expenseAmount = $('#expenseAmount').val().trim();

    // create p tags
    var expenseNameText = $('<p>');
    var expenseAmountText = $('<p>');

    //change the p tags text
    expenseNameText.text(expenseName);
    expenseAmountText.text(expenseAmount);

    //print out the p tags to screen
    $('.expenses-name').append(expenseNameText);
    $('.expenses-amount').append(expenseAmountText);

    //take the expense number input and make it an iterger
    expenseAmountNumber = parseInt(expenseAmount);
    
    //push the intergers into the array
    expenseAmountNumberArr.push(expenseAmountNumber);

    var expenseItems = {
        name: expenseName,
        amount: expenseAmount
    }

    // expenseList.push(expenseItems)
    
    localStorage.setItem("expenseItems", JSON.stringify(expenseItems))

    $('#expenseName').val("")
    $('#expenseAmount').val("")

    expenseTotal()
});

$(".total-expense").find("span").text("0")

var expenseTotal = function() {
    var expenseCounter = 0;

    for (var i = 0; i < expenseAmountNumberArr.length; i++) { 
        expenseCounter = expenseAmountNumberArr[i] + expenseCounter;
    }
    $(".total-expense").find("span").text(expenseCounter)
    
}


$('.add-income').on('click', function() {
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


var incomeTotal = function() {
    var incomeCounter = 0;
    for (var i = 0; i < incomeAmountNumberArr.length; i++) {
        incomeCounter = incomeAmountNumberArr[i] + incomeCounter;
    }
    $('.total-income').find('span').text(incomeCounter);
}

